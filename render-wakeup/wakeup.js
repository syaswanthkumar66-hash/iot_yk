import { env, exit } from 'process';

// Get the backend URL from environment variables, fallback to default Render URL
const backendUrl = env.BACKEND_URL || 'https://iot-yk.onrender.com';
const healthEndpoint = `${backendUrl.replace(/\/$/, '')}/health`;

console.log(`[Wakeup Cron] Starting wakeup request to: ${healthEndpoint}`);

const controller = new AbortController();
const timeoutId = setTimeout(() => controller.abort(), 10000); // 10-second timeout

try {
  const response = await fetch(healthEndpoint, {
    method: 'GET',
    headers: {
      'User-Agent': 'render-wakeup-cron/1.0',
      'Accept': 'application/json'
    },
    signal: controller.signal
  });

  clearTimeout(timeoutId);

  const timestamp = new Date().toISOString();
  console.log(`[Wakeup Cron] [${timestamp}] Response Status: ${response.status} ${response.statusText}`);

  if (response.ok) {
    const data = await response.json();
    console.log('[Wakeup Cron] Response Payload:', JSON.stringify(data, null, 2));
    console.log('[Wakeup Cron] Backend is awake and healthy! ✅');
    exit(0);
  } else {
    const text = await response.text();
    console.error(`[Wakeup Cron] Response Error: ${text}`);
    exit(1);
  }
} catch (error) {
  clearTimeout(timeoutId);
  if (error.name === 'AbortError') {
    console.error('[Wakeup Cron] Error: Connection timed out after 10 seconds.');
  } else {
    console.error('[Wakeup Cron] Connection error occurred:', error.message);
  }
  exit(1);
}

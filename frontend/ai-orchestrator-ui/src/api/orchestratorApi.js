const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export async function submitJob(userId, goal) {
  const response = await fetch(`${BASE_URL}/api/jobs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, goal }),
  });
  if (!response.ok) throw new Error('Failed to submit job');
  return response.json();
}

export async function getJob(jobId) {
  const response = await fetch(`${BASE_URL}/api/jobs/${jobId}`);
  if (!response.ok) throw new Error('Failed to fetch job');
  return response.json();
}

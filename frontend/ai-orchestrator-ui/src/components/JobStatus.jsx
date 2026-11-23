import { useState, useEffect } from "react";
import { getJob, getJobProgress, TERMINAL_STATUSES } from "../api/orchestratorApi";

const STATUS_COLORS = {
  RECEIVED: "#888", PLANNING: "#2980b9", ROUTING: "#8e44ad",
  EXECUTING: "#e67e22", AGGREGATING: "#16a085", COMPLETED: "#27ae60",
  COMPLETED_WITH_ERRORS: "#d68910", FAILED: "#c0392b",
};

export default function JobStatus({ jobId }) {
  const [job, setJob] = useState(null);
  const [progress, setProgress] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let interval;
    async function fetchJob() {
      try {
        const data = await getJob(jobId);
        setJob(data);
        try {
          setProgress(await getJobProgress(jobId));
        } catch {
          /* the progress bar is optional; the status above still works */
        }
        if (TERMINAL_STATUSES.includes(data.status)) clearInterval(interval);
      } catch (e) {
        setError(e.message);
        clearInterval(interval);
      }
    }
    fetchJob();
    interval = setInterval(fetchJob, 3000);
    return () => clearInterval(interval);
  }, [jobId]);

  if (error) return <p style={{ textAlign: "center", color: "#c0392b", marginTop: 80, fontFamily: "system-ui, sans-serif" }}>{error}</p>;
  if (!job) return <p style={{ textAlign: "center", color: "#888", marginTop: 80, fontFamily: "system-ui, sans-serif" }}>Loading...</p>;

  const finished = TERMINAL_STATUSES.includes(job.status);
  const hasAnswer = (job.status === "COMPLETED" || job.status === "COMPLETED_WITH_ERRORS") && job.finalAnswer;

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <span style={styles.jobId}>Job {jobId.slice(0, 8)}...</span>
        <span style={{ ...styles.badge, background: STATUS_COLORS[job.status] || "#888" }}>{job.status}</span>
      </div>
      <div style={styles.card}>
        <p style={styles.label}>Goal</p>
        <p style={styles.value}>{job.goal}</p>
      </div>

      {progress && progress.totalTasks > 0 && (
        <div style={styles.card}>
          <p style={styles.label}>
            Progress: {progress.completed + progress.failed} of {progress.totalTasks} tasks done
            {progress.failed > 0 ? ` (${progress.failed} failed)` : ""}
          </p>
          <div style={styles.barTrack}>
            <div style={{ ...styles.barFill, width: `${progress.percentComplete}%` }} />
          </div>
          {!finished && progress.runningTaskDescriptions && progress.runningTaskDescriptions.length > 0 && (
            <p style={styles.runningNote}>Working on: {progress.runningTaskDescriptions.join(" | ")}</p>
          )}
        </div>
      )}

      {!finished && (
        <div style={styles.polling}>
          <span style={styles.dot} /> Checking for updates...
        </div>
      )}

      {job.status === "COMPLETED_WITH_ERRORS" && (
        <div style={{ ...styles.answerCard, background: "#fffaf0", border: "1px solid #f5deb3", borderLeft: "4px solid #d68910" }}>
          <p style={styles.label}>Partial result</p>
          <p style={styles.value}>{job.failureReason || "Some tasks failed. The answer below covers what succeeded."}</p>
        </div>
      )}

      {hasAnswer && (
        <div style={styles.answerCard}>
          <p style={styles.label}>Final Answer</p>
          <p style={styles.answerText}>{job.finalAnswer}</p>
        </div>
      )}

      {job.status === "FAILED" && (
        <div style={{ ...styles.answerCard, background: "#fff5f5", border: "1px solid #f5c6cb", borderLeft: "4px solid #c0392b" }}>
          <p style={styles.label}>Job Failed</p>
          <p style={styles.value}>{job.failureReason || "The job could not be completed. Please try again."}</p>
        </div>
      )}

      <div style={styles.meta}>
        <span>Created: {new Date(job.createdAt).toLocaleString()}</span>
        <span>Updated: {new Date(job.updatedAt).toLocaleString()}</span>
      </div>
    </div>
  );
}

const styles = {
  container: { maxWidth: 680, margin: "60px auto", padding: "0 24px", fontFamily: "system-ui, sans-serif" },
  header: { display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 24 },
  jobId: { fontSize: 14, color: "#888", fontFamily: "monospace" },
  badge: { padding: "4px 12px", borderRadius: 20, fontSize: 13, fontWeight: 600, color: "#fff" },
  card: { background: "#f9f9f9", border: "1px solid #eee", borderRadius: 8, padding: 16, marginBottom: 16 },
  label: { fontSize: 12, fontWeight: 600, color: "#888", textTransform: "uppercase", letterSpacing: 0.5, marginBottom: 6 },
  value: { fontSize: 15, color: "#222", lineHeight: 1.6, margin: 0 },
  barTrack: { height: 8, background: "#e8e8e8", borderRadius: 4, overflow: "hidden", marginTop: 8 },
  barFill: { height: "100%", background: "#27ae60", transition: "width 0.4s" },
  runningNote: { fontSize: 12, color: "#888", margin: "10px 0 0" },
  polling: { display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "#888", marginBottom: 16 },
  dot: { width: 8, height: 8, borderRadius: "50%", background: "#2980b9" },
  answerCard: { background: "#f0faf4", border: "1px solid #c3e6cb", borderLeft: "4px solid #27ae60", borderRadius: 8, padding: 16, marginBottom: 16 },
  answerText: { fontSize: 15, color: "#222", lineHeight: 1.8, margin: 0, whiteSpace: "pre-wrap" },
  meta: { display: "flex", gap: 24, fontSize: 12, color: "#aaa", marginTop: 8 },
};
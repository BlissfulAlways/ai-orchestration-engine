import { useState } from "react";
import { submitJob } from "../api/orchestratorApi";

export default function SubmitJob({ onJobSubmitted }) {
  const [goal, setGoal] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit() {
    if (!goal.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const data = await submitJob("user-001", goal.trim());
      onJobSubmitted(data.jobId);
    } catch (e) {
      setError("Failed to submit job. Is the backend running?");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={styles.container}>
      <h1 style={styles.title}>AI Orchestration Engine</h1>
      <p style={styles.subtitle}>Enter a goal and the system will break it into tasks, execute them, and return a final answer.</p>
      <textarea
        style={styles.textarea}
        placeholder="e.g. Research the latest trends in AI and write a summary report"
        value={goal}
        onChange={(e) => setGoal(e.target.value)}
        rows={5}
      />
      {error && <p style={styles.error}>{error}</p>}
      <button
        style={loading ? { ...styles.button, ...styles.buttonDisabled } : styles.button}
        onClick={handleSubmit}
        disabled={loading}
      >
        {loading ? "Submitting..." : "Submit Goal"}
      </button>
    </div>
  );
}

const styles = {
  container: { maxWidth: 680, margin: "80px auto", padding: "0 24px", fontFamily: "system-ui, sans-serif" },
  title: { fontSize: 28, fontWeight: 700, marginBottom: 8, color: "#111" },
  subtitle: { fontSize: 15, color: "#555", marginBottom: 24, lineHeight: 1.6 },
  textarea: { width: "100%", padding: 14, fontSize: 15, borderRadius: 8, border: "1px solid #ddd", resize: "vertical", boxSizing: "border-box", outline: "none", lineHeight: 1.6 },
  button: { marginTop: 16, padding: "12px 28px", fontSize: 15, fontWeight: 600, background: "#111", color: "#fff", border: "none", borderRadius: 8, cursor: "pointer" },
  buttonDisabled: { background: "#999", cursor: "not-allowed" },
  error: { color: "#c0392b", fontSize: 14, marginTop: 10 },
};

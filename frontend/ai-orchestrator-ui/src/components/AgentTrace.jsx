import { useState, useEffect } from "react";
import { getJob, getJobTrace } from "../api/orchestratorApi";

const TERMINAL_STATUSES = ["COMPLETED", "FAILED"];

const STATUS_COLORS = {
  COMPLETED: "#27ae60",
  FAILED: "#c0392b",
  PENDING: "#888",
};

export default function AgentTrace({ jobId }) {
  const [trace, setTrace] = useState(null);
  const [error, setError] = useState(null);
  const [expandedSteps, setExpandedSteps] = useState({});

  useEffect(() => {
    let interval;

    async function fetchTrace() {
      try {
        const [jobData, traceData] = await Promise.all([
          getJob(jobId),
          getJobTrace(jobId)
        ]);
        setTrace(traceData);
        if (TERMINAL_STATUSES.includes(jobData.status)) {
          clearInterval(interval);
        }
      } catch (e) {
        setError("Failed to load trace.");
        clearInterval(interval);
      }
    }

    fetchTrace();
    interval = setInterval(fetchTrace, 3000);
    return () => clearInterval(interval);
  }, [jobId]);

  function toggleSteps(index) {
    setExpandedSteps((prev) => ({ ...prev, [index]: !prev[index] }));
  }

  if (error) return <p style={{ textAlign: "center", color: "#c0392b", marginTop: 80, fontFamily: "system-ui, sans-serif" }}>{error}</p>;
  if (!trace) return <p style={{ textAlign: "center", color: "#888", marginTop: 80, fontFamily: "system-ui, sans-serif" }}>Loading trace...</p>;

  return (
    <div style={styles.container}>
      <h2 style={styles.heading}>Agent Trace</h2>
      <p style={styles.subtitle}>Step by step breakdown of how each task was executed.</p>

      {trace.map((task, index) => (
        <div key={index} style={styles.taskCard}>
          <div style={styles.taskHeader}>
            <div style={styles.taskLeft}>
              <span style={styles.sequence}>Task {task.sequenceNumber}</span>
              <span style={styles.agentType}>{task.requiredAgentType}</span>
            </div>
            <span style={{ ...styles.statusBadge, background: STATUS_COLORS[task.status] || "#e67e22" }}>
              {task.status}
            </span>
          </div>

          <p style={styles.taskDescription}>{task.taskDescription}</p>

          {task.resultContent && (
            <div style={styles.resultBox}>
              <p style={styles.label}>Result</p>
              <p style={styles.resultText}>{task.resultContent}</p>
            </div>
          )}

          {task.failureReason && (
            <div style={{ ...styles.resultBox, borderLeft: "3px solid #c0392b", background: "#fff5f5" }}>
              <p style={styles.label}>Failure Reason</p>
              <p style={styles.resultText}>{task.failureReason}</p>
            </div>
          )}

          {task.steps && task.steps.length > 0 && (
            <div>
              <button style={styles.toggleBtn} onClick={() => toggleSteps(index)}>
                {expandedSteps[index]
                  ? `Hide ${task.steps.length} execution steps`
                  : `Show ${task.steps.length} execution steps`}
              </button>

              {expandedSteps[index] && (
                <div style={styles.stepsContainer}>
                  {task.steps.map((step, si) => (
                    <div key={si} style={styles.stepCard}>
                      <p style={styles.stepNumber}>Step {step.stepNumber}</p>

                      <p style={styles.label}>Agent Thought</p>
                      <p style={styles.stepText}>{step.agentThought}</p>

                      {step.toolCalled && (
                        <div style={styles.toolBox}>
                          <p style={styles.label}>Tool Called</p>
                          <code style={styles.toolName}>{step.toolCalled}</code>
                        </div>
                      )}

                      {step.toolResult && step.toolResult !== '""' && (
                        <div style={styles.toolBox}>
                          <p style={styles.label}>Tool Result</p>
                          <p style={styles.stepText}>
                            {step.toolResult.replace(/^"|"$/g, '').substring(0, 500)}
                            {step.toolResult.length > 500 ? "..." : ""}
                          </p>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

const styles = {
  container: { maxWidth: 680, margin: "0 auto", padding: "0 24px 60px", fontFamily: "system-ui, sans-serif" },
  heading: { fontSize: 22, fontWeight: 700, marginBottom: 6, color: "#111" },
  subtitle: { fontSize: 14, color: "#888", marginBottom: 24 },
  taskCard: { border: "1px solid #e5e5e5", borderRadius: 10, padding: 18, marginBottom: 16 },
  taskHeader: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 },
  taskLeft: { display: "flex", alignItems: "center", gap: 10 },
  sequence: { fontSize: 13, fontWeight: 700, color: "#111" },
  agentType: { fontSize: 11, fontWeight: 600, background: "#f0f0f0", color: "#555", padding: "2px 8px", borderRadius: 20 },
  statusBadge: { fontSize: 11, fontWeight: 600, color: "#fff", padding: "2px 10px", borderRadius: 20 },
  taskDescription: { fontSize: 14, color: "#333", lineHeight: 1.6, marginBottom: 12 },
  resultBox: { background: "#f9f9f9", borderLeft: "3px solid #27ae60", borderRadius: 6, padding: 12, marginBottom: 12 },
  label: { fontSize: 11, fontWeight: 700, color: "#888", textTransform: "uppercase", letterSpacing: 0.5, marginBottom: 4 },
  resultText: { fontSize: 13, color: "#333", lineHeight: 1.7, margin: 0, whiteSpace: "pre-wrap" },
  toggleBtn: { background: "none", border: "1px solid #ddd", borderRadius: 6, padding: "6px 14px", fontSize: 13, color: "#555", cursor: "pointer", marginTop: 4 },
  stepsContainer: { marginTop: 12, display: "flex", flexDirection: "column", gap: 10 },
  stepCard: { background: "#fafafa", border: "1px solid #eee", borderRadius: 8, padding: 14 },
  stepNumber: { fontSize: 12, fontWeight: 700, color: "#aaa", marginBottom: 8 },
  stepText: { fontSize: 13, color: "#444", lineHeight: 1.7, margin: 0, whiteSpace: "pre-wrap", wordBreak: "break-word" },
  toolBox: { marginTop: 10 },
  toolName: { fontSize: 13, background: "#f0f0f0", padding: "2px 8px", borderRadius: 4, color: "#2980b9" },
};

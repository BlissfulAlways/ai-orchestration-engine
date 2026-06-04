import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import JobStatus from "../components/JobStatus";
import AgentTrace from "../components/AgentTrace";

export default function JobPage() {
  const { jobId } = useParams();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState("status");

  return (
    <div style={{ fontFamily: "system-ui, sans-serif" }}>
      <div style={styles.nav}>
        <button style={styles.back} onClick={() => navigate("/")}>
          ← New Job
        </button>
      </div>

      <div style={styles.tabs}>
        <button
          style={activeTab === "status" ? { ...styles.tab, ...styles.activeTab } : styles.tab}
          onClick={() => setActiveTab("status")}
        >
          Status
        </button>
        <button
          style={activeTab === "trace" ? { ...styles.tab, ...styles.activeTab } : styles.tab}
          onClick={() => setActiveTab("trace")}
        >
          Agent Trace
        </button>
      </div>

      {activeTab === "status" && <JobStatus jobId={jobId} />}
      {activeTab === "trace" && <AgentTrace jobId={jobId} />}
    </div>
  );
}

const styles = {
  nav: {
    padding: "16px 24px",
    borderBottom: "1px solid #eee",
  },
  back: {
    background: "none",
    border: "none",
    fontSize: 14,
    color: "#555",
    cursor: "pointer",
    padding: 0,
  },
  tabs: {
    display: "flex",
    gap: 0,
    borderBottom: "1px solid #eee",
    padding: "0 24px",
  },
  tab: {
    background: "none",
    border: "none",
    borderBottom: "2px solid transparent",
    padding: "12px 20px",
    fontSize: 14,
    color: "#888",
    cursor: "pointer",
    fontWeight: 500,
  },
  activeTab: {
    color: "#111",
    borderBottom: "2px solid #111",
  },
};

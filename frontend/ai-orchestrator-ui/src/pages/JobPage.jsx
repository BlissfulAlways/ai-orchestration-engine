import { useParams, useNavigate } from "react-router-dom";
import JobStatus from "../components/JobStatus";

export default function JobPage() {
  const { jobId } = useParams();
  const navigate = useNavigate();
  return (
    <div>
      <div style={{ padding: "16px 24px", borderBottom: "1px solid #eee" }}>
        <button style={{ background: "none", border: "none", fontSize: 14, color: "#555", cursor: "pointer", padding: 0 }} onClick={() => navigate("/")}>
          ← New Job
        </button>
      </div>
      <JobStatus jobId={jobId} />
    </div>
  );
}

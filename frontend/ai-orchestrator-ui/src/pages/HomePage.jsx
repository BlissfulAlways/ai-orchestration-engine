import { useNavigate } from "react-router-dom";
import SubmitJob from "../components/SubmitJob";

export default function HomePage() {
  const navigate = useNavigate();
  return <SubmitJob onJobSubmitted={(jobId) => navigate(`/jobs/${jobId}`)} />;
}

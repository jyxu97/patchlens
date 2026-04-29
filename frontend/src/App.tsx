import { Routes, Route } from 'react-router-dom'
import HomePage from './pages/HomePage'
import ResultPage from './pages/ResultPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      {/* :sessionId is a URL parameter, e.g. /result/abc-123 */}
      <Route path="/result/:sessionId" element={<ResultPage />} />
    </Routes>
  )
}

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server on :5173 (gateway CORS allows this origin). All API calls go to VITE_API_BASE.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
})

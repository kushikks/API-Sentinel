import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { developerARoutes } from './routes'
import { ApiError, SessionProvider } from './session'

const query = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5000,
      retry: (tries, error) => tries < 1 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
    },
  },
})
const root = document.getElementById('root')
if (!root) throw new Error('Mount element is missing')
createRoot(root).render(<QueryClientProvider client={query}>
  <SessionProvider><RouterProvider router={createBrowserRouter(developerARoutes)} /></SessionProvider>
</QueryClientProvider>)

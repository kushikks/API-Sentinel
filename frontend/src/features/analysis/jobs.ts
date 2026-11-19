export type JobState = 'QUEUED' | 'RUNNING' | 'RETRY_PENDING' | 'COMPLETED' | 'FAILED'

export type Job = {
  id: string; apiId: string; baselineVersionId: string | null; newVersionId: string; status: JobState;
  attemptCount: number; createdAt: string; completedAt: string | null; error: string | null;
  outcome: 'COMPARED' | 'NO_BASELINE' | null; breakingCount: number; potentiallyBreakingCount: number; nonBreakingCount: number
}

export const statusNames: Record<JobState, string> = {
  QUEUED: 'Queued', RUNNING: 'Running', RETRY_PENDING: 'Waiting to retry', COMPLETED: 'Completed', FAILED: 'Failed',
}

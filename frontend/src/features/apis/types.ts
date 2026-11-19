export type Api = {
  id: string; name: string; description: string | null; ownerId: string; baseUrl: string | null; createdAt: string; updatedAt: string
}
export type Version = {
  id: string; apiId: string; versionLabel: string; specificationHash: string; createdAt: string;
  createdBy: string; status: 'REGISTERED'; contentType: string; sizeBytes: number
}
export type Submission = {
  analysisId: string; apiId: string; newVersionId: string; baselineVersionId: string | null; correlationId: string; createdAt: string
}
export type Upload = { version: Version; analysis: Submission | null }

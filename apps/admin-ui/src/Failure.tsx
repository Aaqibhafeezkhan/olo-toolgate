// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { ApiError,ConfigurationPending } from './api';

/** Present only bounded correlation identifiers and our own human-safe messages. */
export function Failure({ error, retry }: { error: unknown; retry?: () => void }) {
  if(error instanceof ConfigurationPending)return <div className="notice" role="status"><strong>{error.message}</strong><a href={`#config-reviews?id=${encodeURIComponent(error.changeId)}`}>Review configuration draft</a></div>;
  const failure = error instanceof ApiError ? error : new ApiError(0, 'NETWORK');
  return <div className="notice error" role="alert"><strong>{failure.message}</strong>
    {failure.requestId && <small>Reference: {failure.requestId}</small>}
    {retry && <button onClick={retry}>Try again</button>}</div>;
}

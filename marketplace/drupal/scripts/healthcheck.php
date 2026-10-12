<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

/**
 * @file
 * Container health check: GET /health on this container, with the public
 * host name so the trusted-host check passes.
 */

$host = parse_url(getenv('MARKETPLACE_BASE_URL') ?: 'http://localhost', PHP_URL_HOST) ?: 'localhost';
$context = stream_context_create(['http' => ['header' => "Host: $host\r\n", 'timeout' => 4, 'ignore_errors' => TRUE]]);
$body = @file_get_contents('http://127.0.0.1:8080/health', FALSE, $context);
$status = isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m) ? (int) $m[1] : 0;
exit($status === 200 && str_contains((string) $body, '"status":"ok"') ? 0 : 1);

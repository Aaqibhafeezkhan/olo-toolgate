<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use GuzzleHttp\ClientInterface;
use GuzzleHttp\Exception\GuzzleException;
use GuzzleHttp\RequestOptions;

/**
 * Calls the internal helper (docs/api.md §3). Drupal is its only caller.
 */
final class HelperClient {

  public function __construct(
    private readonly ClientInterface $http,
    private readonly MarketplaceSettings $settings,
  ) {}

  /**
   * @param array{version: string, fileRef: string}|null $previous
   */
  public function inspect(string $fileRef, ?array $previous): array {
    return $this->call('POST', '/inspect', ['fileRef' => $fileRef, 'previousVersion' => $previous]);
  }

  public function sign(string $fileRef, string $packageDigest, string $publisherSlug): array {
    return $this->call('POST', '/sign', [
      'fileRef' => $fileRef,
      'packageDigest' => $packageDigest,
      'authorizedPublisherSlug' => $publisherSlug,
    ]);
  }

  public function keys(): array {
    return $this->call('GET', '/keys', NULL);
  }

  private function call(string $method, string $path, ?array $body): array {
    $options = [
      RequestOptions::HEADERS => [
        'Authorization' => 'Bearer ' . $this->settings->get('helper_token'),
        'Accept' => 'application/json',
      ],
      RequestOptions::TIMEOUT => (int) $this->settings->get('helper_timeout') + 15,
      RequestOptions::CONNECT_TIMEOUT => 5,
      RequestOptions::HTTP_ERRORS => FALSE,
      RequestOptions::ALLOW_REDIRECTS => FALSE,
    ];
    if ($body !== NULL) {
      $options[RequestOptions::JSON] = $body;
    }
    try {
      $response = $this->http->request($method, rtrim((string) $this->settings->get('helper_url'), '/') . $path, $options);
    }
    catch (GuzzleException) {
      throw new HelperException('UNAVAILABLE', 'The package checker is unreachable. Try again shortly.', TRUE);
    }
    $status = $response->getStatusCode();
    $data = json_decode((string) $response->getBody(), TRUE);
    if ($status === 200 && is_array($data)) {
      return $data;
    }
    $code = is_array($data) && is_string($data['code'] ?? NULL) ? $data['code'] : 'UNAVAILABLE';
    $message = is_array($data) && is_string($data['message'] ?? NULL) ? $data['message'] : "The package checker answered $status.";
    throw new HelperException($code, $message, $status >= 500 || $status === 401);
  }

}

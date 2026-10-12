<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Core\File\FileSystemInterface;

/**
 * Turns a Drupal private file URI into the helper's fileRef (docs/api.md §3).
 *
 * - In production the private scheme is backed by s3fs, which keeps private
 *   files under "<bucket>/<s3fs-private folder>/<target>", so
 *   private://toolgate/x.tgpkg becomes s3://<bucket>/s3fs-private/toolgate/x.tgpkg
 *   and the helper reads it with its own credentials.
 * - In tests and single-host setups the helper shares a directory with
 *   Drupal and gets "file:<path relative to that directory>".
 */
final class FileRefResolver {

  public function __construct(
    private readonly MarketplaceSettings $settings,
    private readonly FileSystemInterface $fileSystem,
  ) {}

  public function toRef(string $uri): string {
    if (!str_starts_with($uri, 'private://')) {
      throw new \InvalidArgumentException('Packages are only read from private storage.');
    }
    $target = substr($uri, strlen('private://'));
    if ($this->settings->get('file_refs') === 's3') {
      $folder = trim((string) $this->settings->get('s3_private_folder'), '/');
      return 's3://' . $this->settings->get('s3_bucket') . '/' . ($folder === '' ? '' : $folder . '/') . $target;
    }
    $root = realpath((string) $this->settings->get('helper_file_root'));
    $path = $this->fileSystem->realpath($uri);
    if ($root === FALSE || $path === FALSE || !str_starts_with($path, $root . DIRECTORY_SEPARATOR)) {
      throw new \RuntimeException('The private file is not inside the helper file root.');
    }
    return 'file:' . substr($path, strlen($root) + 1);
  }

}

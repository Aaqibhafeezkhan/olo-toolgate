<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Terminal output frame. */
final readonly class ResultFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public ResultType $resultType,
        public array $content,
        public bool $isError,
        public ?\stdClass $structuredContent = null,
        public ?ToolErrorBody $error = null,
        public ?ResultProvenance $provenance = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'resultType', 'content', 'structuredContent', 'isError', 'error', 'provenance']) || array_diff(['kind', 'resultType', 'content', 'isError'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'RESULT') {
            throw new \InvalidArgumentException('kind must be RESULT');
        }
        return new self(
            $data['kind'],
            ResultType::from($data['resultType']),
            array_map(static fn ($item) => (object) $item, $data['content']),
            $data['isError'],
            array_key_exists('structuredContent', $data) ? (object) $data['structuredContent'] : null,
            array_key_exists('error', $data) ? ToolErrorBody::fromArray($data['error']) : null,
            array_key_exists('provenance', $data) ? ResultProvenance::from($data['provenance']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}

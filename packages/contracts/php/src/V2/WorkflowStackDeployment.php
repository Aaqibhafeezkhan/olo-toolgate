<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Complete workflow-scoped local deployment declaration. Operator images and custody remain explicitly trusted; this grants no model or tool authority. */
final readonly class WorkflowStackDeployment implements \JsonSerializable {
    public function __construct(
        public bool $enabled,
        public WorkflowStackRuntime $runtime,
        public WorkflowStackPlatform $platform,
        public WorkflowStackModelEngine $model,
        public WorkflowStackTriggerSource $source,
        public WorkflowStackMonitoring $monitoring,
        public ?WorkflowStackFolderAgent $fileAgent = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['enabled', 'runtime', 'platform', 'model', 'source', 'fileAgent', 'monitoring']) || array_diff(['enabled', 'runtime', 'platform', 'model', 'source', 'monitoring'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['enabled'],
            WorkflowStackRuntime::fromArray($data['runtime']),
            WorkflowStackPlatform::fromArray($data['platform']),
            WorkflowStackModelEngine::fromArray($data['model']),
            WorkflowStackTriggerSource::fromArray($data['source']),
            WorkflowStackMonitoring::fromArray($data['monitoring']),
            array_key_exists('fileAgent', $data) ? WorkflowStackFolderAgent::fromArray($data['fileAgent']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}

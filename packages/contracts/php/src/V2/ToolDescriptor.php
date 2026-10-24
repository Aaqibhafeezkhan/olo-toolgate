<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Semantic tool descriptor, generation 1. Identical in standalone and governed mode. */
final readonly class ToolDescriptor implements \JsonSerializable {
    public function __construct(
        public int $descriptorGeneration,
        public string $toolId,
        public string $mcpName,
        public ToolKind $kind,
        public string $title,
        public string $description,
        public \stdClass $inputSchema,
        public array $errors,
        public ToolEffect $effect,
        public BusinessKeySettings $businessKey,
        public ExecutionSettings $execution,
        public array $routes,
        public array $destinations,
        public array $httpAuth,
        public array $tcp,
        public ToolVariables $variables,
        public array $permissions,
        public array $toolsets,
        public ?string $useWhen = null,
        public ?\stdClass $outputSchema = null,
        public ?string $reconcileTool = null,
        public ?array $tiers = null,
        public ?ToolAuthorization $authorization = null,
        public ?ToolReconciliation $reconciliation = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['descriptorGeneration', 'toolId', 'mcpName', 'kind', 'title', 'description', 'useWhen', 'inputSchema', 'outputSchema', 'errors', 'effect', 'businessKey', 'execution', 'reconcileTool', 'tiers', 'routes', 'destinations', 'httpAuth', 'tcp', 'variables', 'permissions', 'toolsets', 'authorization', 'reconciliation']) || array_diff(['descriptorGeneration', 'toolId', 'mcpName', 'kind', 'title', 'description', 'inputSchema', 'errors', 'effect', 'businessKey', 'execution', 'routes', 'destinations', 'httpAuth', 'tcp', 'variables', 'permissions', 'toolsets'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['descriptorGeneration'] !== 1) {
            throw new \InvalidArgumentException('descriptorGeneration must be 1');
        }
        return new self(
            $data['descriptorGeneration'],
            $data['toolId'],
            $data['mcpName'],
            ToolKind::from($data['kind']),
            $data['title'],
            $data['description'],
            (object) $data['inputSchema'],
            array_map(static fn ($item) => ErrorDeclaration::fromArray($item), $data['errors']),
            ToolEffect::fromArray($data['effect']),
            BusinessKeySettings::fromArray($data['businessKey']),
            ExecutionSettings::fromArray($data['execution']),
            array_map(static fn ($item) => RouteDeclaration::fromArray($item), $data['routes']),
            array_map(static fn ($item) => DestinationDeclaration::fromArray($item), $data['destinations']),
            array_map(static fn ($item) => HttpAuthDeclaration::fromArray($item), $data['httpAuth']),
            array_map(static fn ($item) => TcpDeclaration::fromArray($item), $data['tcp']),
            ToolVariables::fromArray($data['variables']),
            array_map(static fn ($item) => Permission::from($item), $data['permissions']),
            array_map(static fn ($item) => $item, $data['toolsets']),
            array_key_exists('useWhen', $data) ? $data['useWhen'] : null,
            array_key_exists('outputSchema', $data) ? (object) $data['outputSchema'] : null,
            array_key_exists('reconcileTool', $data) ? $data['reconcileTool'] : null,
            array_key_exists('tiers', $data) ? array_map(static fn ($item) => RuntimeTier::from($item), $data['tiers']) : null,
            array_key_exists('authorization', $data) ? ToolAuthorization::fromArray($data['authorization']) : null,
            array_key_exists('reconciliation', $data) ? ToolReconciliation::from($data['reconciliation']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}

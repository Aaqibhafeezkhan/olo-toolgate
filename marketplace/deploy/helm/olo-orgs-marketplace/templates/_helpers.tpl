{{/* Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 */}}
{{- define "olo-orgs.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- define "olo-orgs.selector" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "olo-orgs.labels" -}}
{{ include "olo-orgs.selector" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "olo-orgs.image" -}}
{{- $root := index . 0 -}}{{- $image := index . 1 -}}
{{- $ref := printf "%s/%s" $root.Values.imageRegistry $image.repository -}}
{{- if $image.digest -}}{{ printf "%s@%s" $ref $image.digest }}{{- else -}}{{ printf "%s:%s" $ref (default "dev" $image.tag) }}{{- end -}}
{{- end -}}
{{/* The public host name: probes send it so Drupal's trusted-host check passes. */}}
{{- define "olo-orgs.host" -}}
{{- (urlParse .Values.marketplace.baseUrl).host | splitList ":" | first -}}
{{- end -}}

{{/* Everything a Drupal container (web, deploy Job, cron) shares. */}}
{{- define "olo-orgs.drupalPod" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
automountServiceAccountToken: false
securityContext:
  runAsNonRoot: true
  runAsUser: 33
  runAsGroup: 33
  fsGroup: 33
  seccompProfile:
    type: RuntimeDefault
volumes:
  - name: tmp
    emptyDir:
      sizeLimit: 256Mi
  - name: secrets
    secret:
      secretName: {{ .Values.secrets.drupal }}
      defaultMode: 0440
{{- end -}}
{{- define "olo-orgs.drupalContainer" -}}
image: {{ include "olo-orgs.image" (list . .Values.drupal.image) }}
imagePullPolicy: {{ .Values.drupal.image.pullPolicy }}
env:
  {{- range $name, $value := (include "olo-orgs.drupalEnvData" . | fromYaml) }}
  - name: {{ $name }}
    value: {{ $value | toString | quote }}
  {{- end }}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: [ALL]
volumeMounts:
  - name: tmp
    mountPath: /tmp
  - name: secrets
    mountPath: /run/secrets/drupal
    readOnly: true
{{- end -}}

{{- define "lumina.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lumina.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "lumina.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "lumina.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* selector labels for one role: include "lumina.selector" (dict "ctx" . "role" "api") */}}
{{- define "lumina.selector" -}}
app.kubernetes.io/name: {{ include "lumina.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .role }}
{{- end }}

{{- define "lumina.postgresSecret" -}}
{{- if .Values.postgres.existingSecret }}{{ .Values.postgres.existingSecret }}{{ else }}{{ include "lumina.fullname" . }}-postgres{{ end }}
{{- end }}

{{- define "lumina.apiKeySecret" -}}
{{- if .Values.security.existingSecret }}{{ .Values.security.existingSecret }}{{ else }}{{ include "lumina.fullname" . }}-api-keys{{ end }}
{{- end }}

{{/* Pod environment shared by every role. Call with (dict "ctx" . "role" "engine"). */}}
{{- define "lumina.env" -}}
- name: LUMINA_ROLES
  value: {{ .role | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ printf "lumina-%s" .role | quote }}
- name: POD_NAME
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
{{- with .ctx.Values.tracing.otlpEndpoint }}
- name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- if .ctx.Values.extraEnv }}
{{ toYaml .ctx.Values.extraEnv }}
{{- end }}
{{- end }}

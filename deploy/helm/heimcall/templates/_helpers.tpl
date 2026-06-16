{{- define "heimcall.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "heimcall.labels" -}}
app.kubernetes.io/part-of: heimcall
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* Cluster URL for a service by its key (Service name == key). */}}
{{- define "heimcall.svcUrl" -}}
{{- $name := .name -}}
{{- $port := (index .root.Values.services $name).port -}}
http://{{ $name }}:{{ $port }}
{{- end -}}

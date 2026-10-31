{{/*
Chart name, truncated to the 63 characters Kubernetes allows in a label value.
*/}}
{{- define "carevoice.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Chart label value: name-version.
*/}}
{{- define "carevoice.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Labels shared by every resource of the release.
*/}}
{{- define "carevoice.commonLabels" -}}
helm.sh/chart: {{ include "carevoice.chart" . }}
app.kubernetes.io/part-of: carevoice
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{/*
Selector labels of one service. Call with (dict "root" $ "svc" $svc).
Stable across upgrades: never add labels here that change between releases.
*/}}
{{- define "carevoice.selectorLabels" -}}
app.kubernetes.io/name: {{ .svc.name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
All labels of one service's resources. Call with (dict "root" $ "svc" $svc "component" "server").
*/}}
{{- define "carevoice.labels" -}}
{{ include "carevoice.commonLabels" .root }}
{{ include "carevoice.selectorLabels" . }}
app.kubernetes.io/component: {{ .component | default "server" }}
{{- end -}}

{{/*
Image reference of one service: [registry/]repository:tag. Call with (dict "root" $ "svc" $svc).
Tag precedence: services.<svc>.image.tag > image.tag > .Chart.AppVersion.
*/}}
{{- define "carevoice.image" -}}
{{- $tag := .svc.image.tag | default .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{- if .root.Values.image.registry -}}
{{- printf "%s/%s:%s" .root.Values.image.registry .svc.image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .svc.image.repository $tag -}}
{{- end -}}
{{- end -}}

{{/*
Name of a service's ConfigMap.
*/}}
{{- define "carevoice.configMapName" -}}
{{- printf "%s-config" .name -}}
{{- end -}}

{{/*
Container security context, shared by services and migration Jobs.
*/}}
{{- define "carevoice.containerSecurityContext" -}}
{{- toYaml .Values.containerSecurityContext -}}
{{- end -}}

{{/*
Pod security context, shared by services and migration Jobs.
*/}}
{{- define "carevoice.podSecurityContext" -}}
{{- toYaml .Values.podSecurityContext -}}
{{- end -}}

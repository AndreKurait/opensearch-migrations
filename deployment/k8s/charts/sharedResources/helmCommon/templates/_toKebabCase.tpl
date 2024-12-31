{{/*
Convert a camelcase string to snake case
*/}}
{{- define "toKebabCase" -}}
{{- /* First split by capital letters and add underscore */ -}}
{{- $result := regexReplaceAll "([A-Z])" . "_${1}" -}}
{{- /* Handle potential leading underscore */ -}}
{{- $result := regexReplaceAll "^_" $result "" -}}
{{- /* Replace '_' by '-' */ -}}
{{- $result := regexReplaceAll "_" $result "-" -}}
{{- /* Convert to uppercase */ -}}
{{- $result | lower -}}
{{- end -}}
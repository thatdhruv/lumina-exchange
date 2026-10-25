{{/*
Container shared by all roles. Call with (dict "ctx" . "role" "api" "resources" .Values.api.resources "ports" true).
*/}}
{{- define "lumina.container" -}}
- name: {{ .role }}
  image: "{{ .ctx.Values.image.repository }}:{{ .ctx.Values.image.tag }}"
  imagePullPolicy: {{ .ctx.Values.image.pullPolicy }}
  ports:
    - name: management
      containerPort: 8081
    {{- if eq .role "api" }}
    - name: http
      containerPort: 8080
    - name: grpc
      containerPort: 9090
    {{- end }}
  envFrom:
    - configMapRef:
        name: {{ include "lumina.fullname" .ctx }}-config
    - secretRef:
        name: {{ include "lumina.apiKeySecret" .ctx }}
  env:
    {{- include "lumina.env" (dict "ctx" .ctx "role" .role) | nindent 4 }}
    - name: SPRING_DATASOURCE_USERNAME
      valueFrom:
        secretKeyRef:
          name: {{ include "lumina.postgresSecret" .ctx }}
          key: username
    - name: SPRING_DATASOURCE_PASSWORD
      valueFrom:
        secretKeyRef:
          name: {{ include "lumina.postgresSecret" .ctx }}
          key: password
    {{- if eq .role "engine" }}
    # Static membership: a restarted pod keeps its partitions instead of triggering a rebalance.
    - name: SPRING_KAFKA_CONSUMER_PROPERTIES_GROUP_INSTANCE_ID
      value: $(POD_NAME)
    {{- end }}
  startupProbe:
    httpGet: { path: /actuator/health/liveness, port: management }
    periodSeconds: 3
    failureThreshold: 40
  livenessProbe:
    httpGet: { path: /actuator/health/liveness, port: management }
    periodSeconds: 10
    failureThreshold: 3
  readinessProbe:
    httpGet: { path: /actuator/health/readiness, port: management }
    periodSeconds: 5
    failureThreshold: 3
  resources:
    {{- toYaml .resources | nindent 4 }}
  securityContext:
    runAsNonRoot: true
    runAsUser: 1001
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  volumeMounts:
    - name: tmp
      mountPath: /tmp
{{- end }}

# NetworkPolicy verify on kind (Phase 16 T4)

kindnet (kind's default CNI) **does not enforce NetworkPolicy** — it silently ignores the objects. To prove
the T4 default-deny + per-pair allow-list actually drops traffic, the cluster runs **Cilium** instead, with
**Hubble** for policy-deny observability.

## Bring up the cluster

```bash
kind delete cluster --name heimcall

# CNI disabled so Cilium can own networking
kind create cluster --name heimcall --config deploy/kind/cluster.yaml

# Cilium + Hubble (kubernetes IPAM works on kind out of the box)
helm repo add cilium https://helm.cilium.io && helm repo update cilium
helm install cilium cilium/cilium --version 1.19.5 -n kube-system \
  --set image.pullPolicy=IfNotPresent \
  --set ipam.mode=kubernetes \
  --set hubble.relay.enabled=true \
  --set hubble.metrics.enableOpenMetrics=true \
  --set hubble.metrics.enabled="{drop,flow,policy,tcp,dns}"
kubectl -n kube-system rollout status ds/cilium --timeout=300s
```

`hubble.metrics.enabled` includes `drop` + `policy` so a Prometheus alert can fire on
`hubble_drop_total{reason="POLICY_DENIED"}` / `hubble_policy_verdicts_total{verdict="DENIED"}` (the
"policy-deny alarm" — wired in staging where Prometheus scrapes Cilium).

## Load the local service images (containerd-snapshotter breaks `kind load docker-image`)

```bash
for s in api-gateway identity-service service-catalog-service schedule-service \
         integration-service incident-service escalation-service notification-service; do
  docker save heimcall/$s:0.0.1-SNAPSHOT -o /tmp/$s.tar
  kind load image-archive /tmp/$s.tar --name heimcall
done
```

## Infra + fleet

```bash
kubectl create namespace heimcall
kubectl apply -f deploy/kind/pg-initdb-configmap.yaml
kubectl apply -f deploy/kind/infra.yaml          # postgres, kafka, redis, jaeger
kubectl -n heimcall rollout status deploy/postgres deploy/kafka deploy/redis --timeout=300s

helm install heimcall deploy/helm/heimcall -n heimcall
kubectl -n heimcall rollout status deploy --timeout=600s
```

## Observe denied flows (Hubble)

```bash
cilium hubble port-forward &   # or: kubectl -n kube-system port-forward svc/hubble-relay 4245:80
hubble observe --namespace heimcall --verdict DROPPED -f
```

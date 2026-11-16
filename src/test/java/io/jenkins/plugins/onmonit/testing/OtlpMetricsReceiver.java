package io.jenkins.plugins.onmonit.testing;

import com.google.protobuf.TextFormat;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** In-JVM OTLP/gRPC metrics receiver that records requests, grouped by the cicd.pipeline.run.id resource attribute. */
public class OtlpMetricsReceiver implements AutoCloseable {

    public record Received(ExportMetricsServiceRequest request, Map<String, String> headers) {}

    private static final String RUN_ID = "cicd.pipeline.run.id";
    private static final Context.Key<Map<String, String>> HEADERS = Context.key("onmonit-headers");

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private Server server;

    public OtlpMetricsReceiver start() throws IOException {
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata md, ServerCallHandler<ReqT, RespT> next) {
                Map<String, String> h = new LinkedHashMap<>();
                for (String key : md.keys()) {
                    if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                        continue;
                    }
                    String v = md.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
                    if (v != null) {
                        h.put(key.toLowerCase(Locale.ROOT), v);
                    }
                }
                return Contexts.interceptCall(Context.current().withValue(HEADERS, h), call, md, next);
            }
        };
        MetricsServiceGrpc.MetricsServiceImplBase service = new MetricsServiceGrpc.MetricsServiceImplBase() {
            @Override
            public void export(
                    ExportMetricsServiceRequest request, StreamObserver<ExportMetricsServiceResponse> observer) {
                Map<String, String> h = HEADERS.get();
                received.add(new Received(request, h == null ? Map.of() : h));
                observer.onNext(ExportMetricsServiceResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(ServerInterceptors.intercept(service, capture))
                .build()
                .start();
        return this;
    }

    public String endpoint() {
        return Loopback.httpUrl(server.getPort());
    }

    private static String runIdOf(ResourceMetrics rm) {
        for (KeyValue kv : rm.getResource().getAttributesList()) {
            if (RUN_ID.equals(kv.getKey())) {
                return asString(kv.getValue());
            }
        }
        return null;
    }

    /** Renders an attribute value as text: scalars as their value, composites in protobuf text format. */
    static String asString(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case BOOL_VALUE -> String.valueOf(v.getBoolValue());
            case INT_VALUE -> String.valueOf(v.getIntValue());
            case DOUBLE_VALUE -> String.valueOf(v.getDoubleValue());
            case VALUE_NOT_SET -> "";
            default -> TextFormat.shortDebugString(v);
        };
    }

    /** Every distinct {@code cicd.pipeline.run.id} received so far, in arrival order. */
    public Set<String> runIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Received r : received) {
            for (ResourceMetrics rm : r.request().getResourceMetricsList()) {
                String id = runIdOf(rm);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public List<Received> requestsForRun(String runId) {
        List<Received> out = new ArrayList<>();
        for (Received r : received) {
            for (ResourceMetrics rm : r.request().getResourceMetricsList()) {
                if (runId.equals(runIdOf(rm))) {
                    out.add(r);
                    break;
                }
            }
        }
        return out;
    }

    public Set<String> metricNames(String runId) {
        Set<String> names = new LinkedHashSet<>();
        for (Received r : requestsForRun(runId)) {
            for (ResourceMetrics rm : r.request().getResourceMetricsList()) {
                if (!runId.equals(runIdOf(rm))) {
                    continue;
                }
                for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                    for (Metric m : sm.getMetricsList()) {
                        names.add(m.getName());
                    }
                }
            }
        }
        return names;
    }

    public List<Map<String, String>> resourceAttributes(String runId) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Received r : requestsForRun(runId)) {
            for (ResourceMetrics rm : r.request().getResourceMetricsList()) {
                if (!runId.equals(runIdOf(rm))) {
                    continue;
                }
                Map<String, String> attrs = new LinkedHashMap<>();
                for (KeyValue kv : rm.getResource().getAttributesList()) {
                    attrs.put(kv.getKey(), asString(kv.getValue()));
                }
                out.add(attrs);
            }
        }
        return out;
    }

    public List<Map<String, String>> headers(String runId) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Received r : requestsForRun(runId)) {
            out.add(r.headers());
        }
        return out;
    }

    @Override
    public void close() throws InterruptedException {
        if (server != null) {
            if (!server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP receiver did not terminate within 5s");
            }
        }
    }
}

package io.jenkins.plugins.onmonit.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OtlpMetricsReceiverTest {

    @Test
    void receivesExportWithHeaders() throws Exception {
        exchange();
    }

    @Test
    void receiverWorksInsideJenkinsTestJvm(JenkinsRule j) throws Exception {
        exchange();
    }

    private static void exchange() throws Exception {
        try (OtlpMetricsReceiver receiver = new OtlpMetricsReceiver().start()) {
            URI uri = URI.create(receiver.endpoint());
            ManagedChannel channel = NettyChannelBuilder.forAddress(uri.getHost(), uri.getPort())
                    .usePlaintext()
                    .intercept(new ClientInterceptor() {
                        @Override
                        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                                MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
                            return new ForwardingClientCall.SimpleForwardingClientCall<>(
                                    next.newCall(method, options)) {
                                @Override
                                public void start(Listener<RespT> listener, Metadata headers) {
                                    headers.put(
                                            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                                            "Bearer t");
                                    super.start(listener, headers);
                                }
                            };
                        }
                    })
                    .build();
            try {
                MetricsServiceGrpc.newBlockingStub(channel)
                        .export(ExportMetricsServiceRequest.newBuilder()
                                .addResourceMetrics(ResourceMetrics.newBuilder()
                                        .setResource(Resource.newBuilder()
                                                .addAttributes(KeyValue.newBuilder()
                                                        .setKey("cicd.pipeline.run.id")
                                                        .setValue(AnyValue.newBuilder()
                                                                .setStringValue("42"))))
                                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                                .addMetrics(Metric.newBuilder().setName("m1"))))
                                .build());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            assertThat(receiver.metricNames("42")).isEqualTo(Set.of("m1"));
            assertThat(receiver.headers("42").get(0)).containsEntry("authorization", "Bearer t");
            assertThat(receiver.resourceAttributes("42").get(0)).containsEntry("cicd.pipeline.run.id", "42");
            assertThat(receiver.requestsForRun("42")).hasSize(1);
            assertThat(receiver.runIds()).containsExactly("42");
        }
    }

    @Test
    void attributeValuesRenderedByType() {
        assertThat(OtlpMetricsReceiver.asString(
                        AnyValue.newBuilder().setStringValue("x").build()))
                .isEqualTo("x");
        assertThat(OtlpMetricsReceiver.asString(
                        AnyValue.newBuilder().setIntValue(42).build()))
                .isEqualTo("42");
        assertThat(OtlpMetricsReceiver.asString(
                        AnyValue.newBuilder().setBoolValue(true).build()))
                .isEqualTo("true");
        assertThat(OtlpMetricsReceiver.asString(
                        AnyValue.newBuilder().setDoubleValue(1.5).build()))
                .isEqualTo("1.5");
        assertThat(OtlpMetricsReceiver.asString(AnyValue.getDefaultInstance())).isEmpty();
    }
}

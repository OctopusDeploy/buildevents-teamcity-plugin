package com.octopus.teamcity.opentelemetry.server;

import com.octopus.teamcity.opentelemetry.common.PluginConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode;
import jetbrains.buildServer.serverSide.buildLog.BlockLogMessage;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import jetbrains.buildServer.serverSide.buildLog.LogMessageFilter;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TeamCityBuildListener extends BuildServerAdapter {

    private final OpenTelemetry openTelemetry;
    private static final String ENDPOINT = TeamCityProperties.getProperty(PluginConstants.PROPERTY_KEY_ENDPOINT);
    private final HashMap<String, Span> spanMap;

    public TeamCityBuildListener(EventDispatcher<BuildServerListener> buildServerListenerEventDispatcher) {
        buildServerListenerEventDispatcher.addListener(this);

        SpanProcessor spanProcessor = buildSpanProcessor();

        Resource serviceNameResource = Resource
                .create(Attributes.of(ResourceAttributes.SERVICE_NAME, PluginConstants.SERVICE_NAME));
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(serviceNameResource))
                .addSpanProcessor(spanProcessor)
                .build();
        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
        Loggers.SERVER.info("OpenTelemetry plugin started and BuildListener registered");

        this.spanMap = new HashMap<>();
    }

    private SpanProcessor buildSpanProcessor() {
        Map<String, String> headers = getExporterHeaders();

        OtlpGrpcSpanExporterBuilder spanExporterBuilder = OtlpGrpcSpanExporter.builder();
        headers.forEach(spanExporterBuilder::addHeader);
        spanExporterBuilder.setEndpoint(ENDPOINT);
        SpanExporter spanExporter = spanExporterBuilder.build();

        Loggers.SERVER.debug("Opentelemetry export headers: " + mask(headers.toString()));
        Loggers.SERVER.debug("Opentelemetry export endpoint: " + ENDPOINT);

        return BatchSpanProcessor.builder(spanExporter).build();
    }

    private String mask(String message) {
        final String API_KEY_REGEX = "([a-z0-9]{31})";
        final Pattern apikeyPattern = Pattern.compile(API_KEY_REGEX);
        final String API_KEY_REPLACEMENT_REGEX = "XXXXXXXXXXXXXXXX";

        StringBuilder buffer = new StringBuilder();

        Matcher matcher = apikeyPattern.matcher(message);
        while (matcher.find()) {
            matcher.appendReplacement(buffer, API_KEY_REPLACEMENT_REGEX);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Map<String, String> getExporterHeaders() throws IllegalStateException {
        Properties internalProperties = TeamCityProperties.getAllProperties().first;
        Loggers.SERVER.debug("TeamCity internal properties: " + internalProperties);

        for (Map.Entry<Object,Object> entry : internalProperties.entrySet()) {
            String propertyName = entry.getKey().toString();
            if (propertyName.contains(PluginConstants.PROPERTY_KEY_HEADERS)) {
                Object propertyValue = entry.getValue();
                Loggers.SERVER.debug("Internal Property Name: " + propertyName);
                Loggers.SERVER.debug("Internal Property Value: " + propertyValue);

                return Arrays.stream(propertyValue.toString().split(","))
                        .map(propertyValuesSplit -> propertyValuesSplit.split(":"))
                        .collect(Collectors.toMap(key -> key[0], value -> value[1]));
            }
        }
        throw new IllegalStateException(PluginConstants.EXCEPTION_ERROR_MESSAGE_HEADERS_UNSET);
    }

    @Override
    public void buildStarted(@NotNull SRunningBuild build) {
        super.buildStarted(build);
        String buildTypeId = build.getBuildTypeId();
        String buildName = getBuildName(build);
        Loggers.SERVER.debug("Build started method triggered for " + buildTypeId);

        Span parentSpan = getParentSpan(build);
        Span span = createSpan(buildTypeId, parentSpan);
        this.spanMap.put(buildTypeId, span);
        Loggers.SERVER.info("Tracer initialized and span created for " + buildName);

        try (Scope ignored = parentSpan.makeCurrent()) {
            setParentSpanAttributes(build, buildName, span);
            span.addEvent(PluginConstants.EVENT_STARTED);
            Loggers.SERVER.info(PluginConstants.EVENT_STARTED + " event added to span for build " + buildName);
            this.spanMap.put(buildTypeId, span);
        } catch (Exception e) {
            Loggers.SERVER.error("Exception in Build Start caused by: " + e.getCause() +
                    ", with message: " + e.getMessage() +
                    ", and stacktrace: " + Arrays.toString(e.getStackTrace()));
            if (span != null) {
                span.setStatus(StatusCode.ERROR, PluginConstants.EXCEPTION_ERROR_MESSAGE_DURING_BUILD_START + ": " + e.getMessage());
            }
        }
    }

    private String getBuildName(SRunningBuild build) {
        return build.getBuildType() != null ? build.getBuildType().getName() : null;
    }

    private Span getParentSpan(SRunningBuild build) {
        Tracer tracer = getTracer();
        BuildPromotion[] topParentBuild = build.getBuildPromotion().findTops();
        BuildPromotion buildPromotion = topParentBuild[0];
        Loggers.SERVER.debug("Top Build Parent: " + buildPromotion);
        if (!this.spanMap.containsKey(buildPromotion.getBuildTypeId())) {
            this.spanMap.put(buildPromotion.getBuildTypeId(), tracer.spanBuilder(buildPromotion.getBuildTypeId()).startSpan());
        }
        return this.spanMap.get(buildPromotion.getBuildTypeId());
    }

    private Span createSpan(String buildTypeId, Span parentSpan) {
        Tracer tracer = getTracer();
        return this.spanMap.containsKey(buildTypeId) ?
                this.spanMap.get(buildTypeId) :
                tracer.spanBuilder(buildTypeId).setParent(Context.current().with(parentSpan)).startSpan();
    }

    private Tracer getTracer() {
        return this.openTelemetry.getTracer(PluginConstants.TRACER_INSTRUMENTATION_NAME);
    }

    private void setParentSpanAttributes(SRunningBuild build, String buildName, Span span) {
        if (build.getBuildType() != null) {
            addAttributeToSpan(span, PluginConstants.ATTRIBUTE_PROJECT_NAME, build.getBuildType().getProject().getName());
        }
        if (build.getBranch() != null ) {
            addAttributeToSpan(span, PluginConstants.ATTRIBUTE_BRANCH,  build.getBranch().getName());
        }
        if (!build.getRevisions().isEmpty()) {
            addAttributeToSpan(span, PluginConstants.ATTRIBUTE_COMMIT,  build.getRevisions().iterator().next().getRevisionDisplayName());
        }
        if (build.getProjectExternalId() != null ) {
            addAttributeToSpan(span, PluginConstants.ATTRIBUTE_PROJECT_ID, build.getProjectExternalId());
        }
        addAttributeToSpan(span, PluginConstants.ATTRIBUTE_AGENT_NAME, build.getAgentName());
        addAttributeToSpan(span, PluginConstants.ATTRIBUTE_AGENT_TYPE, build.getAgent().getAgentTypeId());
        addAttributeToSpan(span, PluginConstants.ATTRIBUTE_BUILD_NUMBER, build.getBuildNumber());
        addAttributeToSpan(span, PluginConstants.ATTRIBUTE_SERVICE_NAME,  build.getBuildTypeExternalId());
        addAttributeToSpan(span, PluginConstants.ATTRIBUTE_NAME, buildName);
    }

    private void addAttributeToSpan(Span span, String attributeName, Object attributeValue) {
        Loggers.SERVER.debug("Adding attribute to span " + attributeName + "=" + attributeValue);
        span.setAttribute(attributeName, attributeValue.toString());
    }

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        super.buildFinished(build);
        buildFinishedOrInterrupted(build);
    }

    @Override
    public void buildInterrupted(@NotNull SRunningBuild build) {
        super.buildInterrupted(build);
        buildFinishedOrInterrupted(build);
    }

    private void buildFinishedOrInterrupted (SRunningBuild build) {
        String buildTypeId = build.getBuildTypeId();
        String buildName = getBuildName(build);
        Loggers.SERVER.debug("Build finished method triggered for " + buildTypeId);

        BuildStatistics buildStatistics = build.getBuildStatistics(
                BuildStatisticsOptions.ALL_TESTS_NO_DETAILS);
        Tracer tracer = getTracer();

        if(this.spanMap.containsKey(buildTypeId)) {
            Span span = this.spanMap.get(buildTypeId);
            Loggers.SERVER.info("Tracer initialized and span found for " + buildName);
            try (Scope ignored = span.makeCurrent()){
                createQueuedEventsSpans(build, buildName, tracer, span);
                createBuildStepSpans(build, buildName, tracer, span);
                getArtifactAttributes(build, buildName, span);

                addAttributeToSpan(span, PluginConstants.ATTRIBUTE_SUCCESS_STATUS, build.getBuildStatus().isSuccessful());
                addAttributeToSpan(span, PluginConstants.ATTRIBUTE_FAILED_TEST_COUNT, buildStatistics.getFailedTestCount());
                addAttributeToSpan(span, PluginConstants.ATTRIBUTE_BUILD_PROBLEMS_COUNT, buildStatistics.getCompilationErrorsCount());

                span.addEvent(PluginConstants.EVENT_FINISHED);
                Loggers.SERVER.info(PluginConstants.EVENT_FINISHED + " event added to span for build " + buildName);
            } catch (Exception e) {
                Loggers.SERVER.error("Exception in Build Finish caused by: " + e.getCause() +
                        ", with message: " + e.getMessage() +
                        ", and stacktrace: " + Arrays.toString(e.getStackTrace()));
                span.setStatus(StatusCode.ERROR, PluginConstants.EXCEPTION_ERROR_MESSAGE_DURING_BUILD_FINISH + ": " + e.getMessage());
            } finally {
                span.end();
                this.spanMap.remove(buildTypeId);
            }
        } else {
            Loggers.SERVER.error("Build end triggered for {} and span not found in plugin spanMap", buildTypeId);
        }
    }

    private void createQueuedEventsSpans(SRunningBuild build, String buildName, Tracer tracer, Span span) {
        long startDateTime = build.getQueuedDate().getTime();
        Map<String, BigDecimal> reportedStatics = build.getStatisticValues();
        Loggers.SERVER.info("Retrieving queued event spans for build " + buildName);

        for (Map.Entry<String,BigDecimal> entry : reportedStatics.entrySet()) {
            String key = entry.getKey();
            Loggers.SERVER.debug("Queue item: " + key);
            if (key.contains("queueWaitReason:")) {
                BigDecimal value = entry.getValue();
                Loggers.SERVER.debug("Queue value: " + value);
                Span childSpan = createChildSpan(tracer, span, key, startDateTime);
                List<String> keySplitList = Pattern.compile(":")
                        .splitAsStream(key)
                        .collect(Collectors.toList());
                childSpan.setAttribute(PluginConstants.ATTRIBUTE_NAME, keySplitList.get(1));
                childSpan.setAttribute(PluginConstants.ATTRIBUTE_SERVICE_NAME, keySplitList.get(0));
                childSpan.end(startDateTime + value.longValue(), TimeUnit.MILLISECONDS);
                Loggers.SERVER.info("Queued span added");
                startDateTime+= value.longValue();
            }
        }
    }

    private void createBuildStepSpans(SRunningBuild build, String buildName, Tracer tracer, Span span) {
        Loggers.SERVER.info("Retrieving build step event spans for build " + buildName);
        List<LogMessage> buildStepLogs = getBuildStepLogs(build);
        for (LogMessage logmessage: buildStepLogs) {
            BlockLogMessage blockLogMessage = (BlockLogMessage) logmessage;
            Date finishedDate = blockLogMessage.getFinishDate();
            String buildStepName = blockLogMessage.getText();
            Loggers.SERVER.debug("Build Step " + buildStepName + " with finish time " + finishedDate);
            if (finishedDate != null) {
                Span childSpan = createChildSpan(tracer, span, blockLogMessage.getText(), blockLogMessage.getTimestamp().getTime());
                if (blockLogMessage.getBlockDescription() != null) {
                    childSpan.setAttribute(PluginConstants.ATTRIBUTE_NAME, buildStepName + ": " + blockLogMessage.getBlockDescription());
                } else {
                    childSpan.setAttribute(PluginConstants.ATTRIBUTE_NAME, buildStepName);
                }
                childSpan.setAttribute(PluginConstants.ATTRIBUTE_SERVICE_NAME, blockLogMessage.getBlockType());
                childSpan.end(finishedDate.getTime(),TimeUnit.MILLISECONDS);
                Loggers.SERVER.info("Build step span added");
            }
        }
    }

    private Span createChildSpan(Tracer tracer, Span parentSpan, String spanName, long startTime) {
        Loggers.SERVER.info("Creating child span " + spanName + " under parent " + parentSpan);
        return tracer.spanBuilder(spanName)
                .setParent(Context.current().with(parentSpan))
                .setStartTimestamp(startTime,TimeUnit.MILLISECONDS)
                .startSpan();
    }

    private List<LogMessage> getBuildStepLogs(SRunningBuild build) {
        return build.getBuildLog().getFilteredMessages(new LogMessageFilter() {
            @Override
            public boolean acceptMessage(LogMessage message, boolean lastMessageInParent) {
                return message instanceof BlockLogMessage;
            }
        });
    }

    private void getArtifactAttributes(SRunningBuild build, String buildName, Span span) {
        Loggers.SERVER.info("Retrieving build artifact attributes for build " + buildName);
        BuildArtifacts buildArtifacts = build.getArtifacts(BuildArtifactsViewMode.VIEW_ALL);
        buildArtifacts.iterateArtifacts(artifact -> {
            span.setAttribute(artifact.getName() + PluginConstants.ATTRIBUTE_ARTIFACT_SIZE, artifact.getSize());
            Loggers.SERVER.debug("Build artifact attribute " + artifact.getName() + "=" + artifact.getSize());
            return BuildArtifacts.BuildArtifactsProcessor.Continuation.CONTINUE;
        });
    }
}

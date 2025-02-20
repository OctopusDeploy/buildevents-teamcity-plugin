package com.octopus.teamcity.opentelemetry.server;

import jetbrains.buildServer.serverSide.ParametersPreprocessor;
import jetbrains.buildServer.serverSide.SRunningBuild;
import org.apache.log4j.Logger;
import org.apache.logging.log4j.CloseableThreadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class BuildParameterPreProcessor implements ParametersPreprocessor  {
    private static final Logger LOG = Logger.getLogger(BuildParameterPreProcessor.class.getName());

    @NotNull
    private final BuildStorageManager buildStorageManager;

    BuildParameterPreProcessor(@NotNull BuildStorageManager buildStorageManager) {
        this.buildStorageManager = buildStorageManager;
    }

    @Override
    public void fixRunBuildParameters(@NotNull SRunningBuild build, @NotNull Map<String, String> runParameters, @NotNull Map<String, String> buildParams) {
        try (var ignored = CloseableThreadContext.put("teamcity.build.id", String.valueOf(build.getBuildId()))) {
            LOG.debug(String.format("Looking up launch uuid for for build id %d, to add to the build parameters", build.getBuildId()));
            var traceId = buildStorageManager.getTraceId(build);

            if (traceId == null) {
                LOG.warn(String.format("Unable to get launch uuid for build id %d; we cant set the build parameters", build.getBuildId()));
                return;
            }
            LOG.debug(String.format("Adding trace id '%s' to build parameters for build %d", traceId, build.getBuildId()));

            buildParams.put("env.TEAMCITY_OTEL_PLUGIN_TRACE_ID", traceId);
        }
    }
}

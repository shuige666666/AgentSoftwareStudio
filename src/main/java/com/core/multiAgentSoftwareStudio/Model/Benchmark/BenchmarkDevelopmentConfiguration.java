package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/** 记录阶段二真正控制连续开发会话的预算与验证协议。 */
public record BenchmarkDevelopmentConfiguration(int maxModelTurns, int maxToolCalls,
                                                String verificationProtocol) { }

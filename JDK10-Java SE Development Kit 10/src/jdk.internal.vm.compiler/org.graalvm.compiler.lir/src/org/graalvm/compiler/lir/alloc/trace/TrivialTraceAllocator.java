/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.graalvm.compiler.lir.alloc.trace;

import static org.graalvm.compiler.lir.LIRValueUtil.asVariable;
import static org.graalvm.compiler.lir.LIRValueUtil.isVariable;
import static org.graalvm.compiler.lir.alloc.trace.TraceUtil.isTrivialTrace;

import java.util.EnumSet;

import org.graalvm.compiler.core.common.alloc.Trace;
import org.graalvm.compiler.core.common.alloc.TraceBuilderResult;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.ValueProcedure;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.ssa.SSAUtil;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

/**
 * Allocates a trivial trace i.e. a trace consisting of a single block with no instructions other
 * than the {@link LabelOp} and the {@link JumpOp}.
 */
final class TrivialTraceAllocator extends TraceAllocationPhase<TraceAllocationPhase.TraceAllocationContext> {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, TraceAllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        TraceBuilderResult resultTraces = context.resultTraces;
        assert isTrivialTrace(lir, trace) : "Not a trivial trace! " + trace;
        AbstractBlockBase<?> block = trace.getBlocks()[0];

        AbstractBlockBase<?> pred = TraceUtil.getBestTraceInterPredecessor(resultTraces, block);

        Value[] variableMap = new Value[lir.numVariables()];
        GlobalLivenessInfo livenessInfo = context.livenessInfo;
        collectMapping(block, pred, livenessInfo, variableMap);
        assignLocations(lir, block, livenessInfo, variableMap);
    }

    /**
     * Collects the mapping from variable to location. Additionally the
     * {@link GlobalLivenessInfo#setInLocations incoming location array} is set.
     */
    private static void collectMapping(AbstractBlockBase<?> block, AbstractBlockBase<?> pred, GlobalLivenessInfo livenessInfo, Value[] variableMap) {
        final int[] blockIn = livenessInfo.getBlockIn(block);
        final Value[] predLocOut = livenessInfo.getOutLocation(pred);
        final Value[] locationIn = new Value[blockIn.length];
        for (int i = 0; i < blockIn.length; i++) {
            int varNum = blockIn[i];
            if (varNum >= 0) {
                Value location = predLocOut[i];
                variableMap[varNum] = location;
                locationIn[i] = location;
            } else {
                locationIn[i] = Value.ILLEGAL;
            }
        }
        livenessInfo.setInLocations(block, locationIn);
    }

    /**
     * Assigns the outgoing locations according to the {@link #collectMapping variable mapping}.
     */
    private static void assignLocations(LIR lir, AbstractBlockBase<?> block, GlobalLivenessInfo livenessInfo, Value[] variableMap) {
        final int[] blockOut = livenessInfo.getBlockOut(block);
        final Value[] locationOut = new Value[blockOut.length];
        for (int i = 0; i < blockOut.length; i++) {
            int varNum = blockOut[i];
            locationOut[i] = variableMap[varNum];
        }
        livenessInfo.setOutLocations(block, locationOut);

        // handle outgoing phi values
        ValueProcedure outputConsumer = new ValueProcedure() {
            @Override
            public Value doValue(Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
                if (isVariable(value)) {
                    return variableMap[asVariable(value).index];
                }
                return value;
            }
        };

        JumpOp jump = SSAUtil.phiOut(lir, block);
        // Jumps have only alive values (outgoing phi values)
        jump.forEachAlive(outputConsumer);
    }

}

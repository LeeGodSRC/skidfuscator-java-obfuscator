package dev.skidfuscator.obfuscator.skidasm.cfg;

import com.google.common.collect.Streams;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.CodeUnit;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.invoke.DynamicInvocationExpr;
import org.mapleir.ir.code.expr.invoke.Invocation;
import org.mapleir.ir.code.expr.invoke.Invokable;
import org.mapleir.ir.locals.Local;
import org.mapleir.ir.locals.LocalsPool;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SkidControlFlowGraph extends ControlFlowGraph {
    public SkidControlFlowGraph(LocalsPool locals, MethodNode methodNode) {
        super(locals, methodNode);
    }

    public SkidControlFlowGraph(ControlFlowGraph cfg) {
        super(cfg);
    }

    public Stream<Invocation> staticInvocationStream() {
        return allExprStream()
                .filter(e -> e instanceof Invokable && !(e instanceof DynamicInvocationExpr))
                .map(e -> (Invocation) e);
    }

    public Stream<DynamicInvocationExpr> dynamicInvocationStream() {
        return allExprStream()
                .filter(e -> e instanceof DynamicInvocationExpr)
                .map(e -> (DynamicInvocationExpr) e);
    }

    @Override
    public Stream<CodeUnit> allExprStream() {
        return vertices()
                .stream()
                .filter(e -> !e.isFlagSet(SkidBlock.FLAG_NO_OPAQUE))
                .flatMap(Collection::stream)
                .map(Stmt::enumerateWithSelf)
                .flatMap(Streams::stream);
    }

    public Local getSelfLocal() {
        assert !getMethodNode().isStatic() : "Trying to get instance local on static method";

        return getLocals().get(0);
    }

    public BasicBlock getEntry() {
        return getEntries().iterator().next();
    }
}

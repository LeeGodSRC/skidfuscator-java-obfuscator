package dev.skidfuscator.obfuscator.number.hash;

import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.locals.Local;

public interface HashTransformer {
    SkiddedHash hash(final int starting, final ControlFlowGraph cfg, final PredicateFlowGetter caller);

    int hash (final int starting);

    Expr hash(final ControlFlowGraph cfg, final PredicateFlowGetter expr);
}

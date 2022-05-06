package dev.skidfuscator.obfuscator.predicate.renderer.impl;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventBus;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.clazz.InitClassTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.group.InitGroupTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.method.InitMethodTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.method.PostMethodTransformEvent;
import dev.skidfuscator.obfuscator.number.NumberManager;
import dev.skidfuscator.obfuscator.number.encrypt.impl.XorNumberTransformer;
import dev.skidfuscator.obfuscator.number.hash.HashTransformer;
import dev.skidfuscator.obfuscator.number.hash.impl.BitwiseHashTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowSetter;
import dev.skidfuscator.obfuscator.predicate.opaque.BlockOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.opaque.ClassOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.opaque.MethodOpaquePredicate;
import dev.skidfuscator.obfuscator.skidasm.*;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeBlock;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeConditionalJumpStmt;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeUnconditionalJumpStmt;
import dev.skidfuscator.obfuscator.skidasm.stmt.SkidCopyVarStmt;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.util.OpcodeUtil;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import dev.skidfuscator.obfuscator.util.TypeUtil;
import dev.skidfuscator.obfuscator.util.cfg.Blocks;
import dev.skidfuscator.obfuscator.util.misc.Parameter;
import org.mapleir.asm.MethodNode;
import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.*;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.FieldLoadExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.stmt.ConditionalJumpStmt;
import org.mapleir.ir.code.stmt.FieldStoreStmt;
import org.mapleir.ir.code.stmt.SwitchStmt;
import org.mapleir.ir.code.stmt.UnconditionalJumpStmt;
import org.mapleir.ir.code.stmt.copy.CopyVarStmt;
import org.mapleir.ir.locals.Local;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.*;

public class IntegerBlockPredicateRenderer extends AbstractTransformer {
    public IntegerBlockPredicateRenderer(Skidfuscator skidfuscator, List<Transformer> children) {
        super(skidfuscator,"GEN3 Flow", children);
    }

    public static final boolean DEBUG = false;

    @Listen
    void handle(final InitMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();
        final BlockOpaquePredicate flowPredicate = methodNode.getFlowPredicate();

        final Local local = methodNode
                .getCfg()
                .getLocals()
                .get(methodNode.getCfg().getLocals().getMaxLocals() + 2);

        flowPredicate.setGetter(new PredicateFlowGetter() {
            @Override
            public Expr get(final ControlFlowGraph cfg) {
                return new VarExpr(local, Type.INT_TYPE);
            }
        });

        flowPredicate.setSetter(new PredicateFlowSetter() {
            @Override
            public Stmt apply(Expr expr) {
                return new CopyVarStmt(new VarExpr(local, Type.INT_TYPE), expr);
            }
        });

        final ClassOpaquePredicate classPredicate = methodNode.isStatic() || methodNode.isInit()
                ? methodNode.getParent().getStaticPredicate()
                : methodNode.getParent().getPredicate();

        final MethodOpaquePredicate methodPredicate = methodNode.getPredicate();

        if (methodPredicate == null)
            return;

        methodPredicate.setGetter(new PredicateFlowGetter() {
            @Override
            public Expr get(ControlFlowGraph cfg) {
                final XorNumberTransformer numberTransformer = new XorNumberTransformer();

                int seed;
                PredicateFlowGetter expr;
                if (methodNode.isClinit() || methodNode.isInit() || true) {
                    seed = RandomUtil.nextInt();
                    expr = new PredicateFlowGetter() {
                        @Override
                        public Expr get(ControlFlowGraph cfg) {
                            return new StaticInvocationExpr(
                                    new Expr[]{new ConstantExpr("" + seed, TypeUtil.STRING_TYPE)},
                                    "java/lang/Integer",
                                    "parseInt",
                                    "(Ljava/lang/String;)I"
                            );
                        }
                    };
                } else {
                    seed = classPredicate.get();
                    expr = classPredicate.getGetter();
                }
                return numberTransformer.getNumber(
                        methodPredicate.get(),
                        seed,
                        cfg,
                        expr
                );
            }
        });
    }

    /**
     * This listener handles every class before they are transformed. The objective
     * here is to prepare any sort of opaque predicate stuff before it is used by
     * the transformers.
     *
     * @param event InitClassTransformEvent event containing any necessary ref
     */
    @Listen
    void handle(final InitClassTransformEvent event) {
        final SkidClassNode classNode = event.getClassNode();

        final ClassOpaquePredicate clazzInstancePredicate = skidfuscator
                .getPredicateAnalysis()
                .getClassPredicate(classNode);

        final ClassOpaquePredicate clazzStaticPredicate = skidfuscator
                .getPredicateAnalysis()
                .getClassStaticPredicate(classNode);

        /*
         * Both methods down below are designed to create a specific way to access
         * a class opaque predicate. To ensure the design is as compact and
         * feasible as humanly possible, we need to exempt some scenarios:
         *
         * if no methods are NON-static         -> access directly via constant expression
         * if class is interface or annotation  -> access directly via constant expression
         * else                                 -> access by loading the value of a field
         */
        final Runnable createConstantInstance = () -> {
            /*
             * Here the getter to access the value is a constant expression loading the
             * constant expr.
             *
             * The setter, on the other hand, throws an exception as it expresses a risk
             * that should be circumvented.
             */
            clazzInstancePredicate.setGetter(new PredicateFlowGetter() {
                @Override
                public Expr get(final ControlFlowGraph cfg) {
                    // TODO: Do class instance opaque predicates
                    return new ConstantExpr(
                            clazzInstancePredicate.get(),
                            Type.INT_TYPE
                    );
                }
            });
            clazzInstancePredicate.setSetter(new PredicateFlowSetter() {
                @Override
                public Stmt apply(Expr expr) {
                    throw new IllegalStateException("Cannot set value for a constant getter");
                }
            });
        };
        final Runnable createDynamicInstance = () -> {
            /*
             * Here, we create a new field in which we'll store the value. As of right now
             * the value is loaded by default as a field constant. However, in the future,
             * this should be calling a specific method in a different class to complicate
             * the reverse engineering task.
             *
             * Contrary to the previous getter, this one store the value in a field. This
             * should make things harder for individuals to fuck around with.
             */
            final SkidFieldNode fieldNode = classNode.createField()
                    .access(Opcodes.ACC_PRIVATE)
                    .name(RandomUtil.randomIsoString(10))
                    .desc("I")
                    .value(clazzInstancePredicate.get())
                    .build();

            clazzInstancePredicate.setGetter(new PredicateFlowGetter() {
                @Override
                public Expr get(final ControlFlowGraph cfg) {
                    return new FieldLoadExpr(
                            new VarExpr(
                                    cfg.getLocals().get(0),
                                    Type.getType("L" + classNode.getName() + ";")
                            ),
                            classNode.node.name,
                            fieldNode.node.name,
                            fieldNode.node.desc,
                            false
                    );
                }
            });
            clazzInstancePredicate.setSetter(new PredicateFlowSetter() {
                @Override
                public Stmt apply(Expr expr) {
                    return new FieldStoreStmt(
                            new VarExpr(
                                    expr.getBlock().cfg.getLocals().get(0),
                                    Type.getType("L" + classNode.getName() + ";")
                            ),
                            expr,
                            classNode.node.name,
                            fieldNode.node.name,
                            fieldNode.node.desc,
                            false
                    );
                }
            });

        };

        /*
         * Both methods down below are designed to create a specific way to access
         * a STATIC class opaque predicate. To ensure the design is as compact and
         * feasible as humanly possible, we need to exempt some scenarios:
         *
         * if no methods are static             -> access directly via constant expression
         * if class is interface or annotation  -> access directly via constant expression
         * else                                 -> access by loading the value of a field
         */
        final Runnable createConstantStatic = () -> {
            clazzStaticPredicate.setGetter(new PredicateFlowGetter() {
                @Override
                public Expr get(final ControlFlowGraph cfg) {
                    // TODO: Do class instance opaque predicates
                    return new ConstantExpr(
                            clazzStaticPredicate.get(),
                            Type.INT_TYPE
                    );
                }
            });
            clazzStaticPredicate.setSetter(new PredicateFlowSetter() {
                @Override
                public Stmt apply(Expr expr) {
                    throw new IllegalStateException("Cannot set value for a constant getter");
                }
            });
        };

        final Runnable createDynamicStatic = () -> {
            final SkidFieldNode staticFieldNode = classNode.createField()
                    .access(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                    .name(RandomUtil.randomIsoString(10))
                    .desc("I")
                    .value(clazzStaticPredicate.get())
                    .build();

            clazzStaticPredicate.setGetter(new PredicateFlowGetter() {
                @Override
                public Expr get(final ControlFlowGraph cfg) {
                    return new FieldLoadExpr(
                            null,
                            classNode.node.name,
                            staticFieldNode.node.name,
                            staticFieldNode.node.desc,
                            true
                    );
                }
            });
            clazzStaticPredicate.setSetter(new PredicateFlowSetter() {
                @Override
                public Stmt apply(Expr expr) {
                    return new FieldStoreStmt(
                            null,
                            expr,
                            classNode.node.name,
                            staticFieldNode.node.name,
                            staticFieldNode.node.desc,
                            true
                    );
                }
            });
        };

        final boolean skip = classNode.isInterface()
                || classNode.isAnnotation()
                || classNode.isEnum();

        if (skip) {
            createConstantStatic.run();
            createConstantInstance.run();
        }

        else {
            final boolean addInstance = classNode.getMethods()
                    .stream()
                    .anyMatch(e -> !e.isInit() && !e.isStatic());

            if (addInstance) {
                createDynamicInstance.run();
            } else {
                createConstantInstance.run();
            }

            final boolean addStatic = classNode.getMethods()
                    .stream()
                    .anyMatch(e -> !e.isClinit() && e.isStatic());

            if (addStatic) {
                createDynamicStatic.run();
            } else {
                createDynamicStatic.run();
            }
        }
    }

    @Listen
    void handle(final InitGroupTransformEvent event) {
        final SkidGroup skidGroup = event.getGroup();

        if (skidGroup.getPredicate().getGetter() != null)
            return;

        final boolean entryPoint = skidGroup.getInvokers().isEmpty()
                || skidGroup.isAnnotation();
        Local local = null;
        int stackHeight = -1;
        String desc = null;

        if (!entryPoint) {
            for (MethodNode methodNode : skidGroup.getMethodNodeList()) {
                final SkidMethodNode skidMethodNode = (SkidMethodNode) methodNode;

                stackHeight = OpcodeUtil.getArgumentsSizes(methodNode.getDesc());
                if (methodNode.isStatic()) stackHeight -= 1;

                final Map<String, Local> localMap = new HashMap<>();
                for (Map.Entry<String, Local> stringLocalEntry :
                        skidMethodNode.getCfg().getLocals().getCache().entrySet()) {
                    final String old = stringLocalEntry.getKey();
                    final String oldStringId = old.split("var")[1].split("_")[0];
                    final int oldId = Integer.parseInt(oldStringId);

                    if (oldId < stackHeight) {
                        localMap.put(old, stringLocalEntry.getValue());
                        continue;
                    }
                    final int newId = oldId + 1;

                    final String newVar = old.replace("var" + oldStringId, "var" + Integer.toString(newId));
                    stringLocalEntry.getValue().setIndex(stringLocalEntry.getValue().getIndex() + 1);
                    localMap.put(newVar, stringLocalEntry.getValue());
                }

                skidMethodNode.getCfg().getLocals().getCache().clear();
                skidMethodNode.getCfg().getLocals().getCache().putAll(localMap);

                final Parameter parameter = new Parameter(methodNode.getDesc());
                parameter.addParameter(Type.INT_TYPE);
                methodNode.node.desc = desc = parameter.getDesc();

                if (local == null) {
                    local = skidMethodNode.getCfg().getLocals().get(stackHeight);
                }
            }

            for (SkidInvocation invoker : skidGroup.getInvokers()) {
                assert invoker != null : String.format("Invoker %s is null!", Arrays.toString(skidGroup.getInvokers().toArray()));
                assert invoker.getExpr() != null : String.format("Invoker %s is null!", invoker.getOwner().getDisplayName());

                int index = 0;
                for (Expr argumentExpr : invoker.getExpr().getArgumentExprs()) {
                    assert argumentExpr != null : "Argument of index " + index + " is null!";
                    index++;
                }

                final Expr[] args = new Expr[invoker.getExpr().getArgumentExprs().length + 1];
                System.arraycopy(
                        invoker.getExpr().getArgumentExprs(),
                        0,
                        args,
                        0,
                        invoker.getExpr().getArgumentExprs().length
                );

                final ConstantExpr constant = new ConstantExpr(skidGroup.getPredicate().get());
                args[args.length - 1] = constant;

                invoker.getExpr().setArgumentExprs(args);
                invoker.getExpr().setDesc(desc);

                //System.out.println("Fixed invoker " + invoker.toString());
            }
        }

        final int finalStackHeight = stackHeight;
        final MethodOpaquePredicate blockOpaquePredicate = skidGroup.getPredicate();
        blockOpaquePredicate.setGetter(
                new PredicateFlowGetter() {
                    @Override
                    public Expr get(final ControlFlowGraph cfg) {
                        if (entryPoint) {
                            if (skidGroup.isStatical()) {
                                final ClassOpaquePredicate clazzPredicate = skidfuscator
                                        .getPredicateAnalysis()
                                        .getClassStaticPredicate(
                                                ((SkidMethodNode) skidGroup.first()).getParent()
                                        );
                                return new XorNumberTransformer()
                                        .getNumber(
                                                clazzPredicate.get(),
                                                skidGroup.getPredicate().get(),
                                                cfg,
                                                clazzPredicate.getGetter()
                                        );
                            } else {
                                /*final Expr privateSeed = new ConstantExpr(
                                        Integer.toString(skidGroup.getPredicate().get()),
                                        Type.getType(String.class)
                                );

                                return new StaticInvocationExpr(
                                        new Expr[]{privateSeed},
                                        "java/lang/Integer",
                                        "parseInt",
                                        "(Ljava/lang/String;)I"
                                );*/

                                return skidGroup.getPredicate().getGetter().get(cfg);
                            }
                        } else {
                            return new VarExpr(cfg.getLocals().get(finalStackHeight), Type.INT_TYPE);
                        }
                    }
                }
        );
    }

    @Listen
    void handle(final PostMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();

        final ControlFlowGraph cfg = methodNode.getCfg();
        final BasicBlock entryPoint = cfg.getEntries().iterator().next();
        final SkidBlock seedEntry = (SkidBlock) entryPoint;

        /*
         *    ____     __
         *   / __/__  / /_______ __
         *  / _// _ \/ __/ __/ // /
         * /___/_//_/\__/_/  \_, /
         *                  /___/
         */

        /*
         * Create a stack local to temporarily store the seed getter
         */

        final MethodOpaquePredicate predicate = methodNode.getPredicate();
        PredicateFlowGetter getter = predicate.getGetter();

        // TODO: Figure out why this happens?
        if (getter == null) {
            final SkidGroup group = skidfuscator
                    .getHierarchy()
                    .getGroup(methodNode);

            EventBus.call(new InitGroupTransformEvent(
                    skidfuscator,
                    group
            ));

            getter = group.getPredicate().getGetter();
        }

        PredicateFlowGetter localGetterT = methodNode.getFlowPredicate().getGetter();
        PredicateFlowSetter localSetterT = methodNode.getFlowPredicate().getSetter();

        // TODO: Figure out why this is happening too
        /*if (localGetterT == null || localSetterT == null) {
            EventBus.call(new InitMethodTransformEvent(
                    skidfuscator,
                    methodNode
            ));

            localGetterT = methodNode.getFlowPredicate().getGetter();
            localSetterT = methodNode.getFlowPredicate().getSetter();
        }*/
        assert localGetterT != null : "Local getter for flow is absent";
        assert localSetterT != null : "Local setter for flow is absent";

        final PredicateFlowGetter localGetter = localGetterT;
        final PredicateFlowSetter localSetter = localSetterT;

        final Expr loadedChanged = /*new ConstantExpr(seedEntry.getSeed(), Type.INT_TYPE); */
                new XorNumberTransformer().getNumber(
                        methodNode.getBlockPredicate(seedEntry), // Outcome
                        methodNode.getPredicate().get(), // Entry
                        methodNode.getCfg(),
                        getter
                );

        final Stmt copyVarStmt = localSetter.apply(loadedChanged);
        entryPoint.add(0, copyVarStmt);

        /*
         *    _____         _ __       __
         *   / ___/      __(_) /______/ /_
         *   \__ \ | /| / / / __/ ___/ __ \
         *  ___/ / |/ |/ / / /_/ /__/ / / /
         * /____/|__/|__/_/\__/\___/_/ /_/
         *
         */

        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            new HashSet<>(vertex)
                    .stream()
                    .filter(e -> e instanceof SwitchStmt)
                    .map(e -> (SwitchStmt) e)
                    .forEach(stmt -> {
                        final SkidBlock seededBlock = (SkidBlock) vertex;

                        for (BasicBlock value : stmt.getTargets().values()) {
                            final SkidBlock target = (SkidBlock) value;
                            this.addSeedLoader(
                                    target,
                                    0,
                                    localGetter,
                                    localSetter,
                                    methodNode.getBlockPredicate(seededBlock),
                                    methodNode.getBlockPredicate(target)
                            );

                /*final Local local1 = block.cfg.getLocals().get(block.cfg.getLocals().getMaxLocals() + 2);
                value.add(0, new CopyVarStmt(new VarExpr(local1, Type.getType(String.class)),
                        new ConstantExpr(block.getDisplayName() +" : c-loc - switch : " + target.getSeed())));
                */
                        }

                        if (stmt.getDefaultTarget() == null || stmt.getDefaultTarget() == vertex)
                            return;

                        final SkidBlock dflt = (SkidBlock) stmt.getDefaultTarget();
                        this.addSeedLoader(
                                dflt,
                                0,
                                localGetter,
                                localSetter,
                                methodNode.getBlockPredicate(seededBlock),
                                methodNode.getBlockPredicate(dflt)
                        );
                    });
        }

        /*
         *     ______                     __  _
         *    / ____/  __________  ____  / /_(_)___  ____
         *   / __/ | |/_/ ___/ _ \/ __ \/ __/ / __ \/ __ \
         *  / /____>  </ /__/  __/ /_/ / /_/ / /_/ / / / /
         * /_____/_/|_|\___/\___/ .___/\__/_/\____/_/ /_/
         *                     /_/
         */

        for (ExceptionRange<BasicBlock> blockRange : cfg.getRanges()) {
            LinkedHashMap<Integer, BasicBlock> basicBlockMap = new LinkedHashMap<>();
            List<Integer> sortedList = new ArrayList<>();

            // Save current handler
            final BasicBlock basicHandler = blockRange.getHandler();
            final SkidBlock handler = (SkidBlock) blockRange.getHandler();

            // Create new block handle
            final BasicBlock toppleHandler = new SkidBlock(cfg);
            cfg.addVertex(toppleHandler);
            blockRange.setHandler(toppleHandler);

            // Hasher
            final HashTransformer hashTransformer = new BitwiseHashTransformer();

            // For all block being read
            for (BasicBlock node : blockRange.getNodes()) {
                if (node instanceof FakeBlock)
                    continue;

                // Get their internal seed and add it to the list
                final SkidBlock internal = (SkidBlock) node;

                // Create a new switch block and get it's seeded variant
                final SkidBlock block = new SkidBlock(cfg);
                cfg.addVertex(block);

                // Add a seed loader for the incoming block and convert it to the handler's
                this.addSeedLoader(
                        block,
                        0,
                        localGetter,
                        localSetter,
                        methodNode.getBlockPredicate(internal),
                        methodNode.getBlockPredicate(handler)
                );

                // Jump to handler
                block.add(new FakeUnconditionalJumpStmt(basicHandler));
                cfg.addEdge(new UnconditionalJumpEdge<>(block, basicHandler));

                // Final hashed
                final int hashed = hashTransformer.hash(
                        methodNode.getBlockPredicate(internal),
                        cfg,
                        localGetter
                ).getHash();

                // Add to switch
                basicBlockMap.put(hashed, block);
                cfg.addEdge(new SwitchEdge<>(toppleHandler, block, hashed));
                sortedList.add(hashed);

                // Find egde and transform
                cfg.getEdges(node)
                        .stream()
                        .filter(e -> e instanceof TryCatchEdge)
                        .map(e -> (TryCatchEdge<BasicBlock>) e)
                        .filter(e -> e.erange == blockRange)
                        .findFirst()
                        .ifPresent(cfg::removeEdge);

                // Add new edge
                cfg.addEdge(new TryCatchEdge<>(node, blockRange));
            }

            // Haha get fucked
            // Todo     Fix the other shit to re-enable this; this is for the lil shits
            //          (love y'all tho) that are gonna try reversing this
            /*for (int i = 0; i < 10; i++) {
                // Generate random seed + prevent conflict
                final int seed = RandomUtil.nextInt();
                if (sortedList.contains(seed))
                    continue;

                // Add seed to list
                sortedList.add(seed);

                // Create new switch block
                final BasicBlock block = new BasicBlock(cfg);
                cfg.addVertex(block);

                // Get seeded version and add seed loader
                final SkidBlock seededBlock = getBlock(block);
                seededBlock.addSeedLoader(-1, local, seed, RandomUtil.nextInt());
                block.add(new UnconditionalJumpStmt(basicHandler));
                cfg.addEdge(new UnconditionalJumpEdge<>(block, basicHandler));

                basicBlockMap.put(seed, block);
                cfg.addEdge(new SwitchEdge<>(handler.getBlock(), block, seed));
            }*/

            // Hash
            final Expr hash = hashTransformer.hash(cfg, localGetter);

            // Default switch edge
            final BasicBlock defaultBlock = Blocks.exception(cfg, "Error in hash");
            cfg.addEdge(new DefaultSwitchEdge<>(toppleHandler, defaultBlock));

            // Add switch
            // Todo     Add hashing to prevent dumb bs reversing
            final SwitchStmt stmt = new SwitchStmt(hash, basicBlockMap, defaultBlock);
            toppleHandler.add(stmt);

            // Add unconditional jump edge
            cfg.addEdge(new UnconditionalJumpEdge<>(toppleHandler, basicHandler));
        }

        /*
         *    __  __                           ___ __  _                   __
         *   / / / /___  _________  ____  ____/ (_) /_(_)___  ____  ____ _/ /
         *  / / / / __ \/ ___/ __ \/ __ \/ __  / / __/ / __ \/ __ \/ __ `/ /
         * / /_/ / / / / /__/ /_/ / / / / /_/ / / /_/ / /_/ / / / / /_/ / /
         * \____/_/ /_/\___/\____/_/ /_/\__,_/_/\__/_/\____/_/ /_/\__,_/_/
         *
         */

        for (BasicBlock block : new HashSet<>(cfg.vertices())) {
            new HashSet<>(block)
                    .stream()
                    .filter(e -> e instanceof UnconditionalJumpStmt && !(e instanceof FakeUnconditionalJumpStmt))
                    .map(e -> (UnconditionalJumpStmt) e)
                    .forEach(stmt -> {
                        final int index = block.indexOf(stmt);
                        final SkidBlock seededBlock = (SkidBlock) block;
                        final SkidBlock targetSeededBlock = (SkidBlock) stmt.getTarget();
                        this.addSeedLoader(
                                seededBlock,
                                index,
                                localGetter,
                                localSetter,
                                methodNode.getBlockPredicate(seededBlock),
                                methodNode.getBlockPredicate(targetSeededBlock)
                        );

                        if (DEBUG) {
                            final Local local1 = block.cfg.getLocals().get(block.cfg.getLocals().getMaxLocals() + 2);
                            block.add(index, new SkidCopyVarStmt(
                                            new VarExpr(local1, Type.getType(String.class)),
                                            new ConstantExpr(block.getDisplayName() +" : c-loc - uncond : " + methodNode.getBlockPredicate(targetSeededBlock))
                                    )
                            );
                        }
                    });
        }

        /*
         *    ______                ___ __  _                   __
         *   / ____/___  ____  ____/ (_) /_(_)___  ____  ____ _/ /
         *  / /   / __ \/ __ \/ __  / / __/ / __ \/ __ \/ __ `/ /
         * / /___/ /_/ / / / / /_/ / / /_/ / /_/ / / / / /_/ / /
         * \____/\____/_/ /_/\__,_/_/\__/_/\____/_/ /_/\__,_/_/
         *
         */

        for (BasicBlock block : new HashSet<>(cfg.vertices())) {
            new HashSet<>(block)
                    .stream()
                    .filter(e -> e instanceof ConditionalJumpStmt && !(e instanceof FakeConditionalJumpStmt))
                    .map(e -> (ConditionalJumpStmt) e)
                    .forEach(stmt -> {
                        // TODO: Not necessary for now
                        /*final ConditionalJumpEdge<BasicBlock> edge = block.cfg.getEdges(block).stream()
                                .filter(e -> e instanceof ConditionalJumpEdge && !(e instanceof FakeConditionalJumpEdge))
                                .map(e -> (ConditionalJumpEdge<BasicBlock>) e)
                                .filter(e -> e.dst().equals(stmt.getTrueSuccessor()))
                                .findFirst()
                                .orElse(null);

                        block.cfg.removeEdge(edge);*/

                        final SkidBlock seededBlock = (SkidBlock) block;
                        final BasicBlock target = stmt.getTrueSuccessor();
                        final SkidBlock targetSeeded = (SkidBlock) target;

                        // Add jump and seed
                        final BasicBlock basicBlock = new SkidBlock(block.cfg);
                        final SkidBlock intraSeededBlock = (SkidBlock) basicBlock;
                        this.addSeedLoader(
                                intraSeededBlock,
                                0,
                                localGetter,
                                localSetter,
                                methodNode.getBlockPredicate(seededBlock),
                                methodNode.getBlockPredicate(targetSeeded)
                        );
                        basicBlock.add(new UnconditionalJumpStmt(target));

                        // Add edge
                        basicBlock.cfg.addVertex(basicBlock);
                        basicBlock.cfg.addEdge(new UnconditionalJumpEdge<>(basicBlock, target));

                        // Replace successor
                        stmt.setTrueSuccessor(basicBlock);
                        block.cfg.addEdge(new ConditionalJumpEdge<>(block, basicBlock, stmt.getOpcode()));

                        if (DEBUG) {
                            final Local local1 = block.cfg.getLocals().get(block.cfg.getLocals().getMaxLocals() + 2);
                            block.add(
                                    block.indexOf(stmt),
                                    new SkidCopyVarStmt(
                                            new VarExpr(local1, Type.getType(String.class)),
                                            new ConstantExpr(block.getDisplayName() + " : c-loc - cond : " + methodNode.getBlockPredicate(targetSeeded))
                                    )
                            );
                        }
                    });
        }


        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            if (vertex instanceof FakeBlock)
                continue;

            cfg.getEdges(vertex).stream()
                    .filter(e -> e instanceof ImmediateEdge)
                    .forEach(e -> {
                        final SkidBlock seededBlock = (SkidBlock) e.src();
                        final SkidBlock targetSeededBlock = (SkidBlock) e.dst();
                        this.addSeedLoader(seededBlock,
                                -1,
                                localGetter,
                                localSetter,
                                methodNode.getBlockPredicate(seededBlock),
                                methodNode.getBlockPredicate(targetSeededBlock)
                        );

                        if (DEBUG) {
                            final Local local1 = vertex.cfg.getLocals().get(vertex.cfg.getLocals().getMaxLocals() + 2);
                            vertex.add(vertex.size(), new CopyVarStmt(new VarExpr(local1, Type.getType(String.class)),
                                    new ConstantExpr(vertex.getDisplayName() +" : c-loc - immediate : " + methodNode.getBlockPredicate((SkidBlock) vertex))));
                        }
                    });
        }

        return;
    }


    private void addSeedLoader(final BasicBlock block, final int index, final PredicateFlowGetter getter, final PredicateFlowSetter local, final int value, final int target) {
        final Expr load = NumberManager.encrypt(target, value, block.cfg, getter);
        final Stmt set = local.apply(load);
        if (index < 0)
            block.add(set);
        else
            block.add(index, set);
    }
}

package dev.skidfuscator.obfuscator.event.impl.transform.group;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.impl.transform.GroupTransformEvent;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;

public class FinalGroupTransformEvent extends GroupTransformEvent {
    public FinalGroupTransformEvent(Skidfuscator skidfuscator, SkidGroup group) {
        super(skidfuscator, group);
    }
}

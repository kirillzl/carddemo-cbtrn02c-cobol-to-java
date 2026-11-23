package com.carddemo.batch.cbtrn02c;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Which {@link Fix} switches are on. {@link #original()} reproduces CBTRN02C exactly. */
public final class PostingOptions {

    private final Set<Fix> fixes;

    private PostingOptions(Set<Fix> fixes) {
        this.fixes = Collections.unmodifiableSet(fixes.isEmpty() ? EnumSet.noneOf(Fix.class) : EnumSet.copyOf(fixes));
    }

    public static PostingOptions original() {
        return new PostingOptions(EnumSet.noneOf(Fix.class));
    }

    public static PostingOptions allFixes() {
        return new PostingOptions(EnumSet.allOf(Fix.class));
    }

    public static PostingOptions of(Fix... fixes) {
        return new PostingOptions(fixes.length == 0 ? EnumSet.noneOf(Fix.class) : EnumSet.copyOf(Arrays.asList(fixes)));
    }

    /** Parses "NONE", "ALL" or a comma separated list of {@link Fix} names. */
    public static PostingOptions parse(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("NONE")) {
            return original();
        }
        if (spec.equalsIgnoreCase("ALL")) {
            return allFixes();
        }
        EnumSet<Fix> set = EnumSet.noneOf(Fix.class);
        for (String s : spec.split(",")) {
            set.add(Fix.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        }
        return new PostingOptions(set);
    }

    public boolean has(Fix fix) {
        return fixes.contains(fix);
    }

    public Set<Fix> fixes() {
        return fixes;
    }

    @Override
    public String toString() {
        return fixes.isEmpty() ? "NONE" : fixes.toString();
    }
}

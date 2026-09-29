package dev.stannismod.stellurgy.util.nbt;

public interface ParametrizedFactory<I, O> {

    O create(I param);
}

package ru.workinprogress.booblik.java;

/** The offsets a batch was given. The records are contiguous from {@code baseOffset}. */
public record ProduceResult(long baseOffset, long logEndOffset) {}

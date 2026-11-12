package io.github.shellci.ci.model;

/** A source range: 1-based lines, 0-based char columns, end column exclusive. */
public record Span(int line, int column, int endLine, int endColumn) {
}

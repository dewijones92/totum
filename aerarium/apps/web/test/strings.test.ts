import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { names, strings, tips, units } from '../src/strings.ts';

const SRC = join(import.meta.dirname, '..', 'src');
const LEADING_EMOJI = /^(\p{Extended_Pictographic}|\p{Regional_Indicator}|[0-9#*]️?⃣)/u;
const PLAIN_ATTRIBUTES = new Set(['title', 'aria-label', 'placeholder', 'alt', 'label']);

type Leaf = string | ((...args: never[]) => string);
interface Tree {
  readonly [key: string]: Leaf | Tree | readonly string[];
}

function isLines(value: unknown): value is readonly string[] {
  return Array.isArray(value);
}

function leaves(tree: Tree, path: string[] = []): [string, string][] {
  return Object.entries(tree).flatMap(([key, value]): [string, string][] => {
    const at = [...path, key];
    if (typeof value === 'string') return [[at.join('.'), value]];
    if (isLines(value))
      return value.map((line, i): [string, string] => [[...at, String(i)].join('.'), line]);
    if (typeof value === 'function') {
      const sample = (value as (...args: unknown[]) => string)(7, 'x', 3, 'y');
      return [[at.join('.'), sample]];
    }
    return leaves(value, at);
  });
}

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return tsxFiles(path);
    return name.endsWith('.tsx') ? [path] : [];
  });
}

function hasWords(text: string): boolean {
  return /[A-Za-z]{2,}/.test(text);
}

function isTextLiteral(node: ts.Expression): boolean {
  return (
    ts.isStringLiteral(node) ||
    ts.isNoSubstitutionTemplateLiteral(node) ||
    ts.isTemplateExpression(node)
  );
}

const TEXT_PROPERTIES = new Set(['label', 'title', 'text', 'placeholder', 'name', 'detail']);

function literalText(node: ts.Expression): string {
  if (ts.isTemplateExpression(node))
    return [node.head.text, ...node.templateSpans.map((span) => span.literal.text)].join(' ');
  return node.getText();
}

function textLeaves(node: ts.Expression): ts.Expression[] {
  if (isTextLiteral(node)) return hasWords(literalText(node)) ? [node] : [];
  if (ts.isParenthesizedExpression(node)) return textLeaves(node.expression);
  if (ts.isConditionalExpression(node))
    return [...textLeaves(node.whenTrue), ...textLeaves(node.whenFalse)];
  if (
    ts.isBinaryExpression(node) &&
    [
      ts.SyntaxKind.AmpersandAmpersandToken,
      ts.SyntaxKind.BarBarToken,
      ts.SyntaxKind.QuestionQuestionToken,
    ].includes(node.operatorToken.kind)
  )
    return textLeaves(node.right);
  return [];
}

function inlineText(file: string): string[] {
  const source = ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  const found: string[] = [];
  const where = (node: ts.Node) =>
    `${relative(SRC, file)}:${String(source.getLineAndCharacterOfPosition(node.getStart()).line + 1)}`;
  const report = (node: ts.Node, label: string) => {
    found.push(`${where(node)} ${label}`);
  };
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node) && hasWords(node.text)) {
      report(node, JSON.stringify(node.text.trim()));
    } else if (ts.isJsxExpression(node) && node.expression && !ts.isJsxAttribute(node.parent)) {
      for (const leaf of textLeaves(node.expression)) report(leaf, leaf.getText());
    } else if (
      ts.isJsxAttribute(node) &&
      PLAIN_ATTRIBUTES.has(node.name.getText()) &&
      node.initializer
    ) {
      const value = ts.isJsxExpression(node.initializer)
        ? node.initializer.expression
        : node.initializer;
      if (value)
        for (const leaf of textLeaves(value))
          report(leaf, `${node.name.getText()}=${leaf.getText()}`);
    } else if (
      ts.isCallExpression(node) &&
      /^set[A-Z]/.test(node.expression.getText()) &&
      node.arguments[0]
    ) {
      for (const leaf of textLeaves(node.arguments[0]))
        report(leaf, `${node.expression.getText()}(${leaf.getText()})`);
    } else if (ts.isReturnStatement(node) && node.expression) {
      for (const leaf of textLeaves(node.expression)) report(leaf, `return ${leaf.getText()}`);
    } else if (
      ts.isPropertyAssignment(node) &&
      TEXT_PROPERTIES.has(node.name.getText()) &&
      !ts.isJsxAttribute(node.parent)
    ) {
      for (const leaf of textLeaves(node.initializer))
        report(leaf, `${node.name.getText()}: ${leaf.getText()}`);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

describe('UI strings', () => {
  it('start every visible string with an emoji', () => {
    const missing = leaves(strings).filter(([, text]) => !LEADING_EMOJI.test(text));
    expect(missing).toEqual([]);
  });

  it('keep tooltips, placeholders and screen-reader labels plain', () => {
    const decorated = [...leaves(tips), ...leaves(units)].filter(([, text]) =>
      LEADING_EMOJI.test(text),
    );
    expect(decorated).toEqual([]);
    expect(leaves(names).length).toBeGreaterThan(0);
  });

  it('come from strings.ts, never typed inline in a component', () => {
    expect(tsxFiles(SRC).flatMap(inlineText)).toEqual([]);
  });
});

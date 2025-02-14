package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

class EndsWithExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void nullExpression() {
    EndsWithExpression expression = new EndsWithExpression(null, null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("endsWith(null, null)", expression.prettyPrint());
  }

  @Test
  void undefinedExpression() {
    EndsWithExpression expression =
        new EndsWithExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    assertFalse(expression.evaluate(resolver));
    assertEquals("endsWith(UNDEFINED, \"null\")", expression.prettyPrint());
  }

  @Test
  void stringExpression() {
    EndsWithExpression expression = new EndsWithExpression(DSL.value("abc"), new StringValue("bc"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("endsWith(\"abc\", \"bc\")", expression.prettyPrint());

    expression = new EndsWithExpression(DSL.value("abc"), new StringValue("ab"));
    assertFalse(expression.evaluate(resolver));
    assertEquals("endsWith(\"abc\", \"ab\")", expression.prettyPrint());
  }
}

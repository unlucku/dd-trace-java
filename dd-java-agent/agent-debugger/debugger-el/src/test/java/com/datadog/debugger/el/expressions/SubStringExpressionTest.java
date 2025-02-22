package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

public class SubStringExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void nullExpression() {
    SubStringExpression expression = new SubStringExpression(null, 0, 0);
    assertTrue(expression.evaluate(resolver).isUndefined());
    assertEquals("substring(null, 0, 0)", expression.prettyPrint());
  }

  @Test
  void undefinedExpression() {
    SubStringExpression expression =
        new SubStringExpression(DSL.value(Values.UNDEFINED_OBJECT), 0, 0);
    assertTrue(expression.evaluate(resolver).isUndefined());
    assertEquals("substring(UNDEFINED, 0, 0)", expression.prettyPrint());
  }

  @Test
  void stringExpression() {
    SubStringExpression expression = new SubStringExpression(DSL.value("abc"), 0, 1);
    assertEquals("a", expression.evaluate(resolver).getValue());
    assertEquals("substring(\"abc\", 0, 1)", expression.prettyPrint());
  }

  @Test
  void stringOutOfBoundsExpression() {
    SubStringExpression expression = new SubStringExpression(DSL.value("abc"), 0, 10);
    assertTrue(expression.evaluate(resolver).isUndefined());
    assertEquals("substring(\"abc\", 0, 10)", expression.prettyPrint());
  }
}

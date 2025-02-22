package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.RefResolverHelper;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HasAllExpressionTest {
  private final int testField = 10;

  @Test
  void testNullPredicate() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    HasAllExpression expression = new HasAllExpression(null, null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("hasAll(null, true)", expression.prettyPrint());
    expression = new HasAllExpression(value(Values.UNDEFINED_OBJECT), null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("hasAll(UNDEFINED, true)", expression.prettyPrint());
    expression = new HasAllExpression(value(this), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals(
        "hasAll(com.datadog.debugger.el.expressions.HasAllExpressionTest, true)",
        expression.prettyPrint());
    expression = new HasAllExpression(value(Collections.singletonList(this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("hasAll(List, true)", expression.prettyPrint());
    expression = new HasAllExpression(value(Collections.singletonMap(this, this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("hasAll(Map, true)", expression.prettyPrint());
  }

  @Test
  void testNullHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAllExpression expression = all(null, TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(null, true)", expression.prettyPrint());

    expression = all(null, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(null, false)", expression.prettyPrint());

    expression = all(null, eq(ref("testField"), value(10)));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(null, testField == 10)", expression.prettyPrint());
  }

  @Test
  void testUndefinedHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAllExpression expression = all(value(Values.UNDEFINED_OBJECT), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(UNDEFINED, true)", expression.prettyPrint());

    expression = all(null, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(null, false)", expression.prettyPrint());

    expression = all(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAll(UNDEFINED, testField == 10)", expression.prettyPrint());
  }

  @Test
  void testSingleElementHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    ValueExpression<?> targetExpression = value(this);
    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals(
        "hasAll(com.datadog.debugger.el.expressions.HasAllExpressionTest, true)",
        expression.prettyPrint());

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals(
        "hasAll(com.datadog.debugger.el.expressions.HasAllExpressionTest, false)",
        expression.prettyPrint());

    expression = all(targetExpression, eq(ref("testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals(
        "hasAll(com.datadog.debugger.el.expressions.HasAllExpressionTest, testField == 10)",
        expression.prettyPrint());
  }

  @Test
  void testArrayHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    ValueExpression<?> targetExpression = value(new Object[] {this, "hello"});

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));

    GetMemberExpression fldRef = getMember(ref(ValueReferences.ITERATOR_REF), "testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    expression = all(targetExpression, eq(fldRef, value(10)));
    assertFalse(expression.evaluate(ctx));

    expression = all(targetExpression, eq(itRef, value("hello")));
    assertFalse(expression.evaluate(ctx));

    expression = all(targetExpression, not(isEmpty(itRef)));
    assertTrue(expression.evaluate(ctx));
  }

  @Test
  void testListHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    ValueExpression<?> targetExpression = value(Arrays.asList(this, "hello"));

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));

    ValueRefExpression fldRef = ref(ValueReferences.ITERATOR_REF + "testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    expression = all(targetExpression, eq(fldRef, value(10)));
    assertFalse(expression.evaluate(ctx));

    expression = all(targetExpression, eq(itRef, value("hello")));
    assertFalse(expression.evaluate(ctx));

    expression = all(targetExpression, not(isEmpty(itRef)));
    assertTrue(expression.evaluate(ctx));
  }

  @Test
  void testMapHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", "a");

    ValueExpression<?> targetExpression = value(valueMap);

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));

    expression =
        all(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("a")));
    assertFalse(expression.evaluate(ctx));

    expression =
        all(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("a")));
    assertTrue(expression.evaluate(ctx));
  }
}

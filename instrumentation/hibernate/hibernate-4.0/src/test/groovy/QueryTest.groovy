/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.trace.Span.Kind.CLIENT
import static io.opentelemetry.trace.Span.Kind.INTERNAL

import io.opentelemetry.trace.attributes.SemanticAttributes
import org.hibernate.Query
import org.hibernate.Session

class QueryTest extends AbstractHibernateTest {

  def "test hibernate query.#queryMethodName single call"() {
    setup:

    // With Transaction
    Session session = sessionFactory.openSession()
    session.beginTransaction()
    queryInteraction(session)
    session.getTransaction().commit()
    session.close()

    // Without Transaction
    if (!requiresTransaction) {
      session = sessionFactory.openSession()
      queryInteraction(session)
      session.close()
    }

    expect:
    assertTraces(requiresTransaction ? 1 : 2) {
      // With Transaction
      trace(0, 4) {
        span(0) {
          name "Session"
          kind INTERNAL
          hasNoParent()
          attributes {
          }
        }
        span(1) {
          name expectedSpanName
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
        span(2) {
          kind CLIENT
          childOf span(1)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "h2"
            "${SemanticAttributes.DB_NAME.key()}" "db1"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" String
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "h2:mem:"
          }
        }
        span(3) {
          name "Transaction.commit"
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
      }
      if (!requiresTransaction) {
        // Without Transaction
        trace(1, 3) {
          span(0) {
            name "Session"
            kind INTERNAL
            hasNoParent()
            attributes {
            }
          }
          span(1) {
            name expectedSpanName
            kind INTERNAL
            childOf span(0)
            attributes {
            }
          }
          span(2) {
            name ~/^select /
            kind CLIENT
            childOf span(1)
            attributes {
              "${SemanticAttributes.DB_SYSTEM.key()}" "h2"
              "${SemanticAttributes.DB_NAME.key()}" "db1"
              "${SemanticAttributes.DB_USER.key()}" "sa"
              "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
              "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "h2:mem:"
            }
          }
        }
      }
    }

    where:
    queryMethodName       | expectedSpanName            | requiresTransaction | queryInteraction
    "query/list"          | "from Value"                | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.list()
    }
    "query/executeUpdate" | "update Value set name = ?" | true                | { sess ->
      Query q = sess.createQuery("update Value set name = ?")
      q.setParameter(0, "alyx")
      q.executeUpdate()
    }
    "query/uniqueResult"  | "from Value where id = ?"   | false               | { sess ->
      Query q = sess.createQuery("from Value where id = ?")
      q.setParameter(0, 1L)
      q.uniqueResult()
    }
    "iterate"             | "from Value"                | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.iterate()
    }
    "query/scroll"        | "from Value"                | false               | { sess ->
      Query q = sess.createQuery("from Value")
      q.scroll()
    }
  }

  def "test hibernate query.iterate"() {
    setup:

    Session session = sessionFactory.openSession()
    session.beginTransaction()
    Query q = session.createQuery("from Value")
    Iterator it = q.iterate()
    while (it.hasNext()) {
      it.next()
    }
    session.getTransaction().commit()
    session.close()

    expect:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name "Session"
          kind INTERNAL
          hasNoParent()
          attributes {
          }
        }
        span(1) {
          name "from Value"
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
        span(2) {
          name ~/^select /
          kind CLIENT
          childOf span(1)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "h2"
            "${SemanticAttributes.DB_NAME.key()}" "db1"
            "${SemanticAttributes.DB_USER.key()}" "sa"
            "${SemanticAttributes.DB_STATEMENT.key()}" ~/^select /
            "${SemanticAttributes.DB_CONNECTION_STRING.key()}" "h2:mem:"
          }
        }
        span(3) {
          name "Transaction.commit"
          kind INTERNAL
          childOf span(0)
          attributes {
          }
        }
      }
    }
  }

}
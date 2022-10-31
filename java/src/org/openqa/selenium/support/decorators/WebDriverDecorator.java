// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.support.decorators;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;
import org.openqa.selenium.Alert;
import org.openqa.selenium.Beta;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.virtualauthenticator.VirtualAuthenticator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * This class helps to create decorators for instances of {@link WebDriver} and
 * derived objects, such as {@link WebElement}s and {@link Alert}, that can
 * extend or modify their "regular" behavior. It provides a flexible
 * alternative to subclassing WebDriver.
 * <p>
 * Here is a general usage pattern:
 * <ol>
 *   <li>implement a subclass of WebDriverDecorator that adds something to WebDriver behavior:<br>
 *     <pre><code>
 * public class MyWebDriverDecorator extends WebDriverDecorator { ... }
 *     </code></pre>
 *     (see below for details)</li>
 *   <li>use a decorator instance to decorate a WebDriver object:<br>
 *     <pre><code>
 * WebDriver original = new FirefoxDriver();
 * WebDriver decorated = new MyWebDriverDecorator().decorate(original);
 *     </code></pre></li>
 *   <li>use the decorated WebDriver instead of the original one:<br>
 *    <pre><code>
 * decorated.get("http://example.com/");
 * ...
 * decorated.quit();
 *    </code></pre>
 *   </li>
 * </ol>
 * By subclassing WebDriverDecorator you can define what code should be executed
 * <ul>
 *   <li>before executing a method of the underlying object,</li>
 *   <li>after executing a method of the underlying object,</li>
 *   <li>instead of executing a method of the underlying object,</li>
 *   <li>when an exception is thrown by a method of the underlying object.</li>
 * </ul>
 * The same decorator is used under the hood to decorate all the objects
 * derived from the underlying WebDriver instance. For example,
 * <code>decorated.findElement(someLocator)</code> automatically decorates
 * the returned WebElement.
 * <p>
 * Instances created by the decorator implement all the same interfaces as
 * the original objects.
 * <p>
 * When you implement a decorator there are two main options (that can be used
 * both separately and together):
 * <ul>
 *   <li>if you want to apply the same behavior modification to all methods of
 *   a WebDriver instance and its derived objects you can subclass
 *   WebDriverDecorator and override some of the following methods:
 *   {@link #beforeCall(Decorated, Method, Object[])},
 *   {@link #afterCall(Decorated, Method, Object[], Object)},
 *   {@link #call(Decorated, Method, Object[])} and
 *   {@link #onError(Decorated, Method, Object[], InvocationTargetException)}</li>
 *   <li>if you want to modify behavior of a specific class instances only
 *   (e.g. behaviour of WebElement instances) you can override one of the
 *   overloaded <code>createDecorated</code> methods to create a non-trivial
 *   decorator for the specific class only.</li>
 * </ul>
 * Let's consider both approaches by examples.
 * <p>
 * One of the most widely used decorator examples is a logging decorator.
 * In this case we want to add the same piece of logging code before and after
 * each invoked method:
 * <pre><code>
 *   public class LoggingDecorator extends WebDriverDecorator {
 *     final Logger logger = LoggerFactory.getLogger(Thread.currentThread().getName());
 *
 *     &#x40;Override
 *     public void beforeCall(Decorated&lt;?&gt; target, Method method, Object[] args) {
 *       logger.debug("before {}.{}({})", target, method, args);
 *     }
 *     &#x40;Override
 *     public void afterCall(Decorated&lt;?&gt; target, Method method, Object[] args, Object res) {
 *       logger.debug("after {}.{}({}) =&gt; {}", target, method, args, res);
 *     }
 *   }
 * </code></pre>
 * For the second example let's implement a decorator that implicitly waits
 * for an element to be visible before any click or sendKeys method call.
 * <pre><code>
 *   public class ImplicitlyWaitingDecorator extends WebDriverDecorator {
 *     private WebDriverWait wait;
 *
 *     &#x40;Override
 *     public Decorated&lt;WebDriver&gt; createDecorated(WebDriver driver) {
 *       wait = new WebDriverWait(driver, Duration.ofSeconds(10));
 *       return super.createDecorated(driver);
 *     }
 *     &#x40;Override
 *     public Decorated&lt;WebElement&gt; createDecorated(WebElement original) {
 *       return new DefaultDecorated&lt;&gt;(original, this) {
 *         &#x40;Override
 *         public void beforeCall(Method method, Object[] args) {
 *           String methodName = method.getName();
 *           if ("click".equals(methodName) || "sendKeys".equals(methodName)) {
 *             wait.until(d -&gt; getOriginal().isDisplayed());
 *           }
 *         }
 *       };
 *     }
 *   }
 * </code></pre>
 * This class is not a pure decorator, it allows to not only add new behavior
 * but also replace "normal" behavior of a WebDriver or derived objects.
 * <p>
 * Let's suppose you want to use JavaScript-powered clicks instead of normal
 * ones (yes, this allows to interact with invisible elements, it's a bad
 * practice in general but sometimes it's inevitable). This behavior change
 * can be achieved with the following "decorator":
 * <pre><code>
 *   public class JavaScriptPoweredDecorator extends WebDriverDecorator {
 *     &#x40;Override
 *     public Decorated&lt;WebElement&gt; createDecorated(WebElement original) {
 *       return new DefaultDecorated&lt;&gt;(original, this) {
 *         &#x40;Override
 *         public Object call(Method method, Object[] args) throws Throwable {
 *           String methodName = method.getName();
 *           if ("click".equals(methodName)) {
 *             JavascriptExecutor executor = (JavascriptExecutor) getDecoratedDriver().getOriginal();
 *             executor.executeScript("arguments[0].click()", getOriginal());
 *             return null;
 *           } else {
 *             return super.call(method, args);
 *           }
 *         }
 *       };
 *     }
 *   }
 * </code></pre>
 * It is possible to apply multiple decorators to compose behaviors added
 * by each of them. For example, if you want to log method calls and
 * implicitly wait for elements visibility you can use two decorators:
 * <pre><code>
 *   WebDriver original = new FirefoxDriver();
 *   WebDriver decorated =
 *     new ImplicitlyWaitingDecorator().decorate(
 *       new LoggingDecorator().decorate(original));
 * </code></pre>
 */
@Beta
public class WebDriverDecorator<T extends WebDriver> {
  private Decorated<T> decorated;

  public WebDriverDecorator() {}

  @Deprecated
  public WebDriverDecorator(Class<T> targetClass) {}

  public final T decorate(T original) {
    Require.nonNull("WebDriver", original);

    decorated = createDecorated(original);
    return createProxy(decorated);
  }

  public Decorated<T> getDecoratedDriver() {
    return decorated;
  }

  public Decorated<T> createDecorated(T driver) {
    return new DefaultDecorated<>(driver, this);
  }

  public Decorated<WebElement> createDecorated(WebElement original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<WebDriver.TargetLocator> createDecorated(WebDriver.TargetLocator original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<WebDriver.Navigation> createDecorated(WebDriver.Navigation original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<WebDriver.Options> createDecorated(WebDriver.Options original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<WebDriver.Timeouts> createDecorated(WebDriver.Timeouts original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<WebDriver.Window> createDecorated(WebDriver.Window original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<Alert> createDecorated(Alert original) {
    return new DefaultDecorated<>(original, this);
  }

  public Decorated<VirtualAuthenticator> createDecorated(VirtualAuthenticator original) {
    return new DefaultDecorated<>(original, this);
  }

  public void beforeCall(Decorated<?> target, Method method, Object[] args) {
    throw new NotImplementedException();
  }

  public Object call(Decorated<?> target, Method method, Object[] args) throws Throwable {
    throw new NotImplementedException();
  }

  public void afterCall(Decorated<?> target, Method method, Object[] args, Object res) {
    throw new NotImplementedException();
  }

  public Object onError(
    Decorated<?> target,
    Method method,
    Object[] args,
    InvocationTargetException e) throws Throwable {
    throw new NotImplementedException();
  }

  @SuppressWarnings({"unchecked"})
  protected final <Z> Z createProxy(final Decorated<Z> decorated) {

    final InvocationHandler handler = (proxy, method, args) -> {
      try {
        decorated.beforeCall(method, args);
      } catch (NotImplementedException e) {
        // ignore
      }

      try {
        Object result = decorated.call(method, args);
        try {
          decorated.afterCall(method, result, args);
        } catch (NotImplementedException e) {
          // ignore
        }
        return result;
      } catch (NotImplementedException e) {
        try {
          Object result = method.invoke(decorated.getOriginal(), args);
          try {
            decorated.afterCall(method, result, args);
          } catch (NotImplementedException e1) {
            // ignore
          }
          return result;
        } catch (InvocationTargetException e1) {
          try {
            return decorated.onError(method, e1, args);
          } catch (NotImplementedException e2) {
            throw e1.getTargetException();
          }
        }
      }
    };

    Class<?> clazz = decorated.getOriginal().getClass();
    //noinspection resource
    Class<?> proxy = new ByteBuddy()
      .subclass(clazz)
      .method(ElementMatchers.isPublic()
        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class))))
      .intercept(InvocationHandlerAdapter.of(handler))
      .make()
      .load(clazz.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
      .getLoaded()
      .asSubclass(clazz);

    try {
      return (Z) proxy.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @FunctionalInterface
  protected interface JsonSerializer {
    Object toJson();
  }
}

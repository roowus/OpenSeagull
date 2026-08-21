package com.roowus.openseagull.host

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection helpers for calling into the installed OpenPigeon.
 *
 * Reflection is not a style choice here — [InstalledOpenPigeon] explains why cross-ClassLoader
 * casting is impossible, which leaves reflection as the only way to call their code at all. Given
 * that, the job of this file is to keep the unavoidable reflection *honest*:
 *
 * - **Absence is a value, not an exception.** A method missing from the user's build of OpenPigeon
 *   is an ordinary compatibility outcome. The `…OrNull` functions return `null` for it, so the
 *   caller has to decide what to do rather than discovering it as a crash in the field.
 *
 * - **A throwing callee is not the same as a missing one.** [InvocationTargetException] wraps an
 *   exception thrown by *their* code, which means we found and called the right method and it
 *   failed on its own terms. Collapsing that into the same `null` as "no such method" would hide
 *   real bugs behind an apparent version mismatch, so [ForeignCallException] keeps them distinct
 *   and preserves the original cause.
 */

/** Thrown when a foreign method was found and invoked, but *their* code threw. */
class ForeignCallException(
    val target: String,
    cause: Throwable,
) : RuntimeException("Call into installed OpenPigeon failed: $target", cause)

/** Find a method, or `null` if this build of OpenPigeon does not declare it. */
fun Class<*>.methodOrNull(name: String, vararg params: Class<*>): Method? = try {
    getMethod(name, *params)
} catch (_: NoSuchMethodException) {
    null
}

/**
 * Instantiate via the no-argument constructor, or `null` if there isn't a usable one.
 *
 * Returns `null` for a missing/inaccessible constructor; rethrows a constructor that ran and threw
 * as [ForeignCallException], for the reason given in the file KDoc.
 */
fun Class<*>.newInstanceOrNull(): Any? = try {
    getDeclaredConstructor().newInstance()
} catch (_: NoSuchMethodException) {
    null
} catch (_: IllegalAccessException) {
    null
} catch (_: InstantiationException) {
    // Abstract class or interface — structurally not instantiable.
    null
} catch (e: InvocationTargetException) {
    throw ForeignCallException("${name}.<init>", e.cause ?: e)
}

/**
 * Invoke [name] on [receiver], or return `null` if the method is absent.
 *
 * [params] must be given explicitly rather than inferred from [args]: inference would pick the
 * runtime class of each argument, which fails for primitives (`Long` vs `long`), for `null`
 * arguments, and for any parameter declared as a supertype of what is passed.
 */
fun Class<*>.invokeOrNull(
    receiver: Any?,
    name: String,
    params: Array<Class<*>> = emptyArray(),
    args: Array<Any?> = emptyArray(),
): Any? {
    val m = methodOrNull(name, *params) ?: return null
    return try {
        m.invoke(receiver, *args)
    } catch (e: InvocationTargetException) {
        // `this.name` explicitly: the `name` parameter shadows Class.name inside this function,
        // and a target of "getPoster.getPoster" would say nothing about *whose* method threw.
        throw ForeignCallException("${this.name}.$name", e.cause ?: e)
    } catch (_: IllegalAccessException) {
        null
    }
}

/** Read a static field (e.g. an `R.drawable` constant), or `null` if absent. */
fun Class<*>.staticIntOrNull(field: String): Int? = try {
    getField(field).getInt(null)
} catch (_: NoSuchFieldException) {
    null
} catch (_: IllegalAccessException) {
    null
}

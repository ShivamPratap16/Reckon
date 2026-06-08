package com.reckon.auth.config

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

@Component
class CurrentUserResolver :
    HandlerMethodArgumentResolver,
    WebMvcConfigurer {
    override fun supportsParameter(p: MethodParameter) = p.hasParameterAnnotation(CurrentUser::class.java) && p.parameterType == UUID::class.java
    override fun resolveArgument(p: MethodParameter, c: ModelAndViewContainer?, w: NativeWebRequest, b: WebDataBinderFactory?): Any? =
        SecurityContextHolder.getContext().authentication?.principal as? UUID
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(this)
    }
}

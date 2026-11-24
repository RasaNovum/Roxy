package net.rasanovum.roxy.bridge;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RoxyPoseStackBridge {
    private static final ClassValue<Methods> METHODS = new ClassValue<>() {
        @Override
        protected Methods computeValue(Class<?> type) {
            try {
                Method quat = type.getMethod("mulPose", Quaternionf.class);
                Method mat = type.getMethod("mulPose", Matrix4f.class);
                return new Methods(quat, mat);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Unable to resolve PoseStack.mulPose overloads on " + type.getName(), exception);
            }
        }
    };

    private RoxyPoseStackBridge() {
    }

    public static void mulPoseQuaternion(Object poseStack, Quaternionfc quaternion) {
        if (poseStack == null || quaternion == null) return;
        Quaternionf mutable = quaternion instanceof Quaternionf direct ? direct : new Quaternionf(quaternion);
        invoke(METHODS.get(poseStack.getClass()).quaternion, poseStack, mutable);
    }

    public static void mulPoseMatrix(Object poseStack, Matrix4fc matrix) {
        if (poseStack == null || matrix == null) return;
        Matrix4f mutable = matrix instanceof Matrix4f direct ? direct : new Matrix4f(matrix);
        invoke(METHODS.get(poseStack.getClass()).matrix, poseStack, mutable);
    }

    private static void invoke(Method method, Object target, Object argument) {
        try {
            method.invoke(target, argument);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("PoseStack.mulPose invocation failed", cause);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("PoseStack.mulPose invocation failed", exception);
        }
    }

    private record Methods(Method quaternion, Method matrix) {
    }
}

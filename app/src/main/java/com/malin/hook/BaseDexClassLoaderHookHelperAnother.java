package com.malin.hook;

import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.PathClassLoader;

/**
 * 由于应用程序使用的ClassLoader为PathClassLoader 最终继承自 BaseDexClassLoader
 * 查看源码得知,这个BaseDexClassLoader加载代码根据一个叫做dexElements的数组进行,
 * 因此我们把包含代码的dex文件插入这个数组. 系统的classLoader就能帮助我们找到这个类
 * <p>
 * 把插件的相关信息放入BaseDexClassLoader的表示dex文件的数组里面,
 * 这样宿主程序的ClassLoader在进行类加载,遍历这个数组的时候,
 * 会自动遍历到我们添加进去的插件信息,从而完成插件类的加载！
 * <p>
 * 这个类用来进行对于BaseDexClassLoader的Hook
 * 使用makePathElements()或者makeDexElements()方法生成插件的Element[]
 */
final class BaseDexClassLoaderHookHelperAnother {

    /*
     * 默认情况下performLaunchActivity会使用替身StubActivity的ApplicationInfo也就是宿主程序的ClassLoader加载所有的类;
     * 我们的思路是告诉宿主ClassLoader我们在哪,让其帮助完成类加载的过程.
     * <p>
     * 宿主程序的ClassLoader最终继承自BaseDexClassLoader,BaseDexClassLoader通过DexPathList进行类的查找过程;
     * 而这个查找通过遍历一个dexElements的数组完成;
     * <p>
     * 我们通过把插件dex添加进这个数组就让宿主ClassLoader获取了加载插件类的能力.
     * <p>
     * 系统使用ClassLoader findClass的过程,发现应用程序使用的非系统类都是通过同一个PathClassLoader加载的;
     * 而这个类的最终父类BaseDexClassLoader通过DexPathList完成类的查找过程;我们hack了这个查找过程,从而完成了插件类的加载
     */

    /**
     * 使用宿主ClassLoader帮助加载插件类
     * java.lang.IllegalAccessError: Class ref in pre-verified class resolved to unexpected implementation
     * 在插件apk和宿主中包含了相同的Jar包;解决方法,插件编译时使用compileOnly依赖和宿主相同的依赖.
     * https://blog.csdn.net/berber78/article/details/41721877
     *
     * @param baseDexClassLoader 表示宿主的LoadedApk在Application类中有一个成员变量mLoadedApk,而这个变量是从ContextImpl中获取的;
     *                           ContextImpl重写了getClassLoader方法,
     *                           因此我们在Context环境中直接getClassLoader()获取到的就是宿主程序唯一的ClassLoader.
     * @param apkFile            apkFile
     */
    static void patchClassLoader(ClassLoader baseDexClassLoader, File apkFile) {

        // -->PathClassLoader
        // -->BaseDexClassLoader
        // -->BaseDexClassLoader中DexPathList pathList
        // -->DexPathList中 Element[] dexElements
        try {
            //0.获取PathClassLoader的父类dalvik.system.BaseDexClassLoader的Class对象
            Class<?> baseDexClassLoaderClazz = PathClassLoader.class.getSuperclass();
            if (baseDexClassLoaderClazz == null) return;

            //1.获取BaseDexClassLoader的成员DexPathList pathList
            //private final DexPathList pathList;
            //http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java
            Field pathListField = baseDexClassLoaderClazz.getDeclaredField("pathList");
            pathListField.setAccessible(true);

            //2.获取DexPathList pathList实例;
            Object dexPathList = pathListField.get(baseDexClassLoader);
            if (dexPathList == null) return;


            //3.获取DexPathList的成员: Element[] dexElements 的Field
            //private Element[] dexElements;
            //http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java
            Field dexElementsField = dexPathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);

            //4.获取DexPathList的成员 Element[] dexElements 的值
            //Element是DexPathList的内部类
            Object[] dexElements = (Object[]) dexElementsField.get(dexPathList);
            if (dexElements == null) return;

            //5.获取dexElements数组的类型 (Element)
            // 数组的 class 对象的getComponentType()方法可以取得一个数组的Class对象
            Class<?> elementClazz = dexElements.getClass().getComponentType();
            if (elementClazz == null) return;

            //6.创建一个数组, 用来替换原始的数组
            //通过Array.newInstance()可以反射生成数组对象,生成数组,指定元素类型和数组长度
            Object[] hostAndPluginElements = (Object[]) Array.newInstance(elementClazz, dexElements.length + 1);


            //根据不同的API, 获取插件DexClassLoader的 DexPathList中的 dexElements数组


            File optimizedDirectory = PluginUtils.getPluginOptDexDir("com.malin.plugin");
            Object[] pluginElements;

            //7.创建插件element数组
            if (Build.VERSION.SDK_INT >= 23) {
                //1.
                List<File> files = new ArrayList<>();
                files.add(apkFile);
                List<IOException> suppressedExceptions = new ArrayList<>();

                //2.
                //private static Element[] makePathElements(List<File> files, File optimizedDirectory, List<IOException> suppressedExceptions)
                Method makePathElementsMethod = dexPathList.getClass().getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                makePathElementsMethod.setAccessible(true);

                //3.
                pluginElements = (Object[]) makePathElementsMethod.invoke(null, files, optimizedDirectory, suppressedExceptions);
            } else if (Build.VERSION.SDK_INT >= 19) {
                //1.
                ArrayList<File> files = new ArrayList<>();
                files.add(apkFile);
                ArrayList<IOException> suppressedExceptions = new ArrayList<>();

                //2.
                //private static Element[] makeDexElements(ArrayList<File> files,File optimizedDirectory,ArrayList<IOException> suppressedExceptions)
                Method makeDexElementsMethod = dexPathList.getClass().getDeclaredMethod("makeDexElements", ArrayList.class, File.class, ArrayList.class);
                makeDexElementsMethod.setAccessible(true);

                //3.
                pluginElements = (Object[]) makeDexElementsMethod.invoke(null, files, optimizedDirectory, suppressedExceptions);
            } else {
                //1.
                ArrayList<File> files = new ArrayList<>();
                files.add(apkFile);

                //2.
                //private static Element[] makeDexElements(ArrayList<File> files,File optimizedDirectory)
                Method makeDexElementsMethod = dexPathList.getClass().getDeclaredMethod("makeDexElements", ArrayList.class, File.class);
                makeDexElementsMethod.setAccessible(true);
                pluginElements = (Object[]) makeDexElementsMethod.invoke(null, files, optimizedDirectory);
            }

            if (pluginElements == null) return;

            //public static native void arraycopy(Object src,  int  srcPos, Object dest, int destPos, int length)
            //* @param      src      the source array.
            //* @param      srcPos   starting position in the source array.
            //* @param      dest     the destination array.
            //* @param      destPos  starting position in the destination data.
            //* @param      length   the number of array elements to be copied.
            //https://blog.csdn.net/wenzhi20102321/article/details/78444158

            //8.把插件的element数组复制进去
            System.arraycopy(pluginElements, 0, hostAndPluginElements, 0, pluginElements.length);

            //9.把宿主的elements复制进去
            System.arraycopy(dexElements, 0, hostAndPluginElements, pluginElements.length, dexElements.length);

            //10.替换
            dexElementsField.set(dexPathList, hostAndPluginElements);

            // 简要总结一下这种方式的原理:
            // 默认情况下performLaunchActivity会使用替身StubActivity的ApplicationInfo也就是宿主程序的ClassLoader加载所有的类;
            // 我们的思路是告诉宿主ClassLoader我们在哪,让其帮助完成类加载的过程.
            // 宿主程序的ClassLoader最终继承自BaseDexClassLoader,BaseDexClassLoader通过DexPathList进行类的查找过程;
            // 而这个查找通过遍历一个dexElements的数组完成;
            // 我们通过把插件dex添加进这个数组就让宿主ClassLoader获取了加载插件类的能力.
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }
}

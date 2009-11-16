/**
 * This file is part of Erjang - A JVM-based Erlang VM
 *
 * Copyright (c) 2009 by Trifork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package erjang;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import erjang.jbeam.ops.CodeAdapter;

public abstract class ETuple extends ETerm implements Cloneable {

	public ETuple testTuple() {
		return this;
	}

	public abstract int arity();

	public abstract EObject elm(int i);

	public static ETuple make(int len) {
		switch (len) {
		case 0:
			return new ETuple0();
		case 1:
			return new ETuple1();
		case 2:
			return new ETuple2();
		case 3:
			return new ETuple3();
		case 4:
			return new ETuple4();
		default:
			return make_big(len);
		}
	}

	public static ETuple make(EObject... array) {
		ETuple res = make(array.length);
		for (int i = 0; i < array.length; i++) {
			res.set(i + 1, array[i]);
		}
		return res;
	}

	public abstract void set(int index, EObject term);

	public abstract ETuple blank();

	private static final Type ETUPLE_TYPE = Type.getType(ETuple.class);
	private static final String ETUPLE_NAME = ETUPLE_TYPE.getInternalName();
	private static final Type ETERM_TYPE = Type.getType(EObject.class);

	private static Map<Integer,ETuple> protos = new HashMap<Integer, ETuple>();

	@SuppressWarnings("unchecked")
	private static ETuple make_big(int size) {

		ETuple proto = protos.get(size);
		
		if (proto == null) {
			try {
				Class<? extends ETuple> c = get_tuple_class(size);
				protos.put(size, proto = c.newInstance());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		return proto.blank();
	}

	static class XClassLoader extends ClassLoader {
		public XClassLoader() {
			super(ETuple.class.getClassLoader());
		}

		Class<?> define(String name, byte[] b) {
			return super.defineClass(name, b, 0, b.length);
		}
	}

	static XClassLoader loader2 = new XClassLoader();

	// "definer" holds a reference to ClassLoader#defineClass
	static private final Method definer;
	static {
		try {
			definer = ClassLoader.class.getDeclaredMethod("defineClass",
					new Class[] { String.class, byte[].class, int.class,
							int.class });
			definer.setAccessible(true);
		} catch (Exception e) {
			throw new ErlangError(e);
		}
	}

	@SuppressWarnings("unchecked")
	static public Class get_tuple_class(int num_cells)  {

		try {
			return Class.forName(ETuple.class.getName() + num_cells);
		} catch (ClassNotFoundException e) {
			// make it!
		}

		byte[] data = make_tuple_class_data(num_cells);

		String name = (ETUPLE_NAME + num_cells).replace('/', '.');

		/*
		 * Class<? extends ETuple> res = (Class<? extends ETuple>)
		 * loader2.define(name, data);
		 */
		Class<? extends ETuple> res;
		try {
			res = (Class<? extends ETuple>) definer.invoke(
					ETuple.class.getClassLoader(), name, data, 0, data.length);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		if (!name.equals(res.getName())) {
			throw new Error();
		}

		return res;
	}

	private static byte[] make_tuple_class_data(int num_cells) {
		ClassWriter cww = new ClassWriter(ClassWriter.COMPUTE_FRAMES
				| ClassWriter.COMPUTE_MAXS);

		CheckClassAdapter cw = new CheckClassAdapter(cww);

		String this_class_name = ETUPLE_NAME + num_cells;
		String super_class_name = ETUPLE_NAME;
		cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
				this_class_name, null, super_class_name, null);

		// create fields

		for (int i = 1; i <= num_cells; i++) {
			cw.visitField(Opcodes.ACC_PUBLIC, "elem" + i, ETERM_TYPE
					.getDescriptor(), null, null);
		}

		// create count method
		create_count(cw, num_cells);

		// create constructor
		create_constructor(cw, super_class_name);

		// create copy
		create_tuple_copy(num_cells, cw, this_class_name, super_class_name);

		// create nth
		create_tuple_nth(num_cells, cw, this_class_name);

		// create set
		create_tuple_set(num_cells, cw, this_class_name);

		cw.visitEnd();
		byte[] data = cww.toByteArray();

		dump(this_class_name, data);

		return data;
	}

	private static void create_tuple_copy(int i, ClassAdapter cw,
			String this_class_name, String super_class_name) {
		MethodVisitor mv;
		make_blank_bridge(cw, this_class_name, super_class_name);

		mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "blank", "()L" + this_class_name
				+ ";", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, this_class_name);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, this_class_name, "<init>",
				"()V");

		mv.visitInsn(Opcodes.ARETURN);

		mv.visitMaxs(3, 3);
		mv.visitEnd();
	}

	private static void make_blank_bridge(ClassAdapter cw,
			String this_class_name, String super_class_name) {
		MethodVisitor mv;
		mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC
				| Opcodes.ACC_BRIDGE, "blank", "()L" + super_class_name + ";",
				null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, this_class_name, "blank",
				"()L" + this_class_name + ";");
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	private static void create_tuple_nth(int n_cells, ClassAdapter cw,
			String this_class_name) {
		MethodVisitor mv;
		mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "elm", "(I)"
				+ ETERM_TYPE.getDescriptor(), null, null);
		mv.visitCode();

		Label dflt = new Label();
		Label[] labels = new Label[n_cells];
		for (int i = 0; i < n_cells; i++) {
			labels[i] = new Label();
		}

		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitTableSwitchInsn(1, n_cells, dflt, labels);

		for (int zbase = 0; zbase < n_cells; zbase++) {

			mv.visitLabel(labels[zbase]);

			mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
			String field = "elem" + (zbase + 1);

			mv.visitFieldInsn(Opcodes.GETFIELD, this_class_name, field,
					ETERM_TYPE.getDescriptor());
			mv.visitInsn(Opcodes.ARETURN);
		}

		mv.visitLabel(dflt);

		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, ETUPLE_NAME, "bad_nth", "(I)"
				+ ETERM_TYPE.getDescriptor());
		mv.visitInsn(Opcodes.ARETURN); // make compiler happy

		mv.visitMaxs(3, 2);
		mv.visitEnd();
	}

	private static void create_tuple_set(int n_cells, ClassAdapter cw,
			String this_class_name) {
		MethodVisitor mv;
		mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "set", "(I"
				+ ETERM_TYPE.getDescriptor() + ")V", null, null);
		mv.visitCode();

		Label dflt = new Label();
		Label[] labels = new Label[n_cells];
		for (int i = 0; i < n_cells; i++) {
			labels[i] = new Label();
		}

		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitTableSwitchInsn(1, n_cells, dflt, labels);

		for (int zbase = 0; zbase < n_cells; zbase++) {

			mv.visitLabel(labels[zbase]);

			mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
			mv.visitVarInsn(Opcodes.ALOAD, 2); // load term

			String field = "elem" + (zbase + 1);

			mv.visitFieldInsn(Opcodes.PUTFIELD, this_class_name, field,
					ETERM_TYPE.getDescriptor());
			mv.visitInsn(Opcodes.RETURN);
		}

		mv.visitLabel(dflt);

		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, ETUPLE_NAME, "bad_nth", "(I)"
				+ ETERM_TYPE.getDescriptor());
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN); // make compiler happy

		mv.visitMaxs(3, 3);
		mv.visitEnd();
	}

	protected final ETerm bad_nth(int i) {
		throw new IllegalArgumentException();
	}

	private static void create_count(ClassAdapter cw, int n) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "arity", "()I",
				null, null);
		mv.visitCode();

		if (n <= 5) {
			mv.visitInsn(Opcodes.ICONST_0 + n);
		} else {
			mv.visitLdcInsn(new Integer(n));
		}
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	private static void create_constructor(ClassAdapter cw,
			String super_class_name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
				null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, super_class_name, "<init>",
				"()V");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	private static void dump(String name, byte[] data) {
		FileOutputStream fo;
		try {
			fo = new FileOutputStream(name.replace('/', '.') + ".class");
			fo.write(data);
			fo.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws IOException {
		byte[] data = make_tuple_class_data(6);
		FileOutputStream fo = new FileOutputStream("ETuple6.class");
		fo.write(data);
		fo.close();

		ETuple val = make_big(7);

		val.set(6, new EString("hello"));

		System.out.println(val);
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer("{");
		for (int i = 1; i <= this.arity(); i++) {
			if (i != 1)
				sb.append(',');
			sb.append(elm(i));
		}
		sb.append("}");
		return sb.toString();
	}

	@Override
	public Type emit_const(CodeAdapter fa) {

		Type type = Type.getType(this.getClass());

		fa.visitTypeInsn(Opcodes.NEW, type.getInternalName());
		fa.visitInsn(Opcodes.DUP);
		fa.visitMethodInsn(Opcodes.INVOKESPECIAL, type.getInternalName(),
				"<init>", "()V");

		for (int i = 0; i < arity(); i++) {
			fa.visitInsn(Opcodes.DUP);

			fa.emit_const(elm(i + 1));

			fa.visitFieldInsn(Opcodes.PUTFIELD, type.getInternalName(), "elem"
					+ (i + 1), ETERM_TYPE.getDescriptor());
		}

		return type;
	}

}

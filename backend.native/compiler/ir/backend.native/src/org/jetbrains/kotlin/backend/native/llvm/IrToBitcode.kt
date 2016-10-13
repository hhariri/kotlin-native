package org.jetbrains.kotlin.backend.native.llvm

import llvm.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid


fun emitLLVM(module: IrModuleFragment, runtimeFile: String, outFile: String) {
    val llvmModule = LLVMModuleCreateWithName("out")!! // TODO: dispose
    val runtime = Runtime(runtimeFile) // TODO: dispose
    LLVMSetDataLayout(llvmModule, runtime.dataLayout)
    LLVMSetTarget(llvmModule, runtime.target)

    val context = Context(module, runtime, llvmModule) // TODO: dispose

    module.accept(RTTIGeneratorVisitor(context), null)
    module.accept(CodeGeneratorVisitor(context), null)
    LLVMWriteBitcodeToFile(llvmModule, outFile)
}

internal class RTTIGeneratorVisitor(context: Context) : IrElementVisitorVoid {
    val generator = RTTIGenerator(context)

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
        super.visitClass(declaration)
        generator.generate(declaration.descriptor)
    }

}

internal class CodeGeneratorVisitor(val context: Context) : IrElementVisitorVoid {
    val generator = CodeGenerator(context)
    override fun visitFunction(declaration: IrFunction) {
        generator.function(declaration)
        declaration.acceptChildrenVoid(this)
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitSetVariable(expression: IrSetVariable) {
        val value = evaluateExpression(generator.tmpVariable(), expression.value)
        generator.store(value!!, generator.variable(expression.descriptor.name.asString())!!)
    }

    private fun evaluateExpression(tmpVariableName: String, value: IrExpression?): LLVMOpaqueValue? {
        when (value) {
            is IrCall -> return evaluateCall(tmpVariableName, value)
            is IrGetValue -> return generator.load(generator.variable(value.descriptor.name.asString())!!, tmpVariableName)
            null -> return null
            else -> {
                TODO()
            }
        }
    }

    private fun evaluateCall(tmpVariableName: String, value: IrCall?): LLVMOpaqueValue? {
        /* TODO: should we count on receiver?
         * val tmp = tmpVar()
         * val lhs = evaluateExpression(tmp, value.dispatchReceiver!!)
         */
        val args = mutableListOf<LLVMOpaqueValue?>()
        value!!.acceptChildrenVoid(object:IrElementVisitorVoid{
            override fun visitElement(element: IrElement) {
                val tmp = generator.tmpVariable()
                args.add(evaluateExpression(tmp, element as IrExpression))
            }
        })
        when (value.descriptor) {
            is FunctionDescriptor -> return evaluateFunctionCall(tmpVariableName, value, args)
            else -> {
                TODO()
            }
        }

        TODO()
    }

    private fun evaluateSimpleFunctionCall(tmpVariableName: String, value: IrCall, args: MutableList<LLVMOpaqueValue?>): LLVMOpaqueValue? {
        return generator.call(value.descriptor as org.jetbrains.kotlin.descriptors.FunctionDescriptor, args, tmpVariableName)
    }


    private fun evaluateFunctionCall(tmpVariableName: String, callee: IrCall, args: MutableList<LLVMOpaqueValue?>): LLVMOpaqueValue? {
        val descriptor:FunctionDescriptor = callee.descriptor as FunctionDescriptor
        when {
            descriptor.isOperator -> return evaluateOperatorCall(tmpVariableName, callee, args)
            else -> {
                return evaluateSimpleFunctionCall(tmpVariableName, callee, args)
            }
        }
    }

    private fun evaluateOperatorCall(tmpVariableName: String, callee: IrCall, args: MutableList<LLVMOpaqueValue?>): LLVMOpaqueValue {
        when (callee!!.origin) {
            IrStatementOrigin.PLUS -> return generator.plus(args[0]!!, args[1]!!, tmpVariableName)
            IrStatementOrigin.MINUS -> return generator.minus(args[0]!!, args[1]!!, tmpVariableName)
            IrStatementOrigin.MUL -> return generator.mul(args[0]!!, args[1]!!, tmpVariableName)
            IrStatementOrigin.DIV -> return generator.div(args[0]!!, args[1]!!, tmpVariableName)
            IrStatementOrigin.PERC -> return generator.remainder(args[0]!!, args[1]!!, tmpVariableName)
            else -> {
                TODO()
            }
        }
    }

    override fun visitVariable(declaration: IrVariable) {
        val variableName = declaration.descriptor.name.asString()
        val variableType = declaration.descriptor.type
        generator.registerVariable(variableName, generator.alloca(variableType, variableName))

        evaluateExpression(variableName, declaration.initializer)
    }

    override fun visitReturn(expression: IrReturn) {
        val tmpVarName = generator.tmpVariable()
        val value:LLVMOpaqueValue?
        value = evaluateExpression(tmpVarName, expression.value)
        LLVMBuildRet(context.llvmBuilder, value)
    }
}
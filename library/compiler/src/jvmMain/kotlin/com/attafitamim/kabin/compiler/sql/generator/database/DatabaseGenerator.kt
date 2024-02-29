package com.attafitamim.kabin.compiler.sql.generator.database

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.attafitamim.kabin.compiler.sql.generator.dao.DaoGenerator
import com.attafitamim.kabin.compiler.sql.generator.mapper.MapperGenerator
import com.attafitamim.kabin.compiler.sql.generator.queries.QueriesGenerator
import com.attafitamim.kabin.compiler.sql.generator.references.ColumnAdapterReference
import com.attafitamim.kabin.compiler.sql.generator.tables.TableGenerator
import com.attafitamim.kabin.compiler.sql.utils.poet.DRIVER_NAME
import com.attafitamim.kabin.compiler.sql.utils.poet.SCHEME_NAME
import com.attafitamim.kabin.compiler.sql.utils.poet.asInitializer
import com.attafitamim.kabin.compiler.sql.utils.poet.asPropertyName
import com.attafitamim.kabin.compiler.sql.utils.poet.buildSpec
import com.attafitamim.kabin.compiler.sql.utils.poet.references.getPropertyName
import com.attafitamim.kabin.compiler.sql.utils.poet.writeType
import com.attafitamim.kabin.compiler.sql.utils.spec.getDaoClassName
import com.attafitamim.kabin.compiler.sql.utils.spec.getDatabaseClassName
import com.attafitamim.kabin.compiler.sql.utils.spec.getQueryClassName
import com.attafitamim.kabin.core.database.KabinDatabase
import com.attafitamim.kabin.processor.ksp.options.KabinOptions
import com.attafitamim.kabin.specs.database.DatabaseSpec
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName

class DatabaseGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: KabinOptions
) {

    private val tableGenerator = TableGenerator(codeGenerator, logger, options)
    private val mapperGenerator = MapperGenerator(codeGenerator, logger, options)
    private val queriesGenerator = QueriesGenerator(codeGenerator, logger, options)
    private val daoGenerator = DaoGenerator(codeGenerator, logger, options)

    fun generate(databaseSpec: DatabaseSpec) {
        val generatedTables = LinkedHashSet<TableGenerator.Result>()
        val generatedMappers = LinkedHashSet<MapperGenerator.Result>()
        val generatedQueries = LinkedHashSet<QueriesGenerator.Result>()
        val requiredAdapters = LinkedHashSet<ColumnAdapterReference>()

        databaseSpec.entities.forEach { entitySpec ->
            val tableResult = tableGenerator.generate(entitySpec)
            generatedTables.add(tableResult)

            val mapperResult = mapperGenerator.generate(entitySpec)
            generatedMappers.add(mapperResult)
            requiredAdapters.addAll(mapperResult.adapters)
        }

        databaseSpec.daoGetterSpecs.forEach { databaseDaoGetterSpec ->
            val daoSpec = databaseDaoGetterSpec.daoSpec

            val queriesResult = queriesGenerator.generate(daoSpec)
            generatedQueries.add(queriesResult)
            requiredAdapters.addAll(queriesResult.adapters)

            daoGenerator.generate(daoSpec)
        }

        generateDatabase(
            databaseSpec,
            generatedTables,
            generatedMappers,
            generatedQueries,
            requiredAdapters
        )
    }

    private fun generateDatabase(
        databaseSpec: DatabaseSpec,
        generatedTables: Set<TableGenerator.Result>,
        generatedMappers: Set<MapperGenerator.Result>,
        generatedQueries: Set<QueriesGenerator.Result>,
        requiredAdapters: Set<ColumnAdapterReference>
    ) {
        val className = databaseSpec.getDatabaseClassName(options)
        val superInterface = KabinDatabase::class.asClassName()
        val databaseInterface = databaseSpec.declaration.toClassName()

        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(superInterface)
            .addSuperinterface(databaseInterface)

        val driverName = DRIVER_NAME
        val constructorBuilder = FunSpec.constructorBuilder()
            .addParameter(driverName, SqlDriver::class.asClassName())

        classBuilder.primaryConstructor(constructorBuilder.build())

        requiredAdapters.forEach { adapter ->
            val propertyName = adapter.getPropertyName()
            val adapterType = ColumnAdapter::class.asClassName()
                .parameterizedBy(adapter.kotlinType, adapter.affinityType)

            val propertyBuilder = PropertySpec.builder(
                propertyName,
                adapterType,
                KModifier.PRIVATE
            ).initializer("null")

            classBuilder.addProperty(propertyBuilder.build())
        }

        generatedMappers.forEach { generatedMapper ->
            val propertyName = generatedMapper.className.asPropertyName()
            val parameters = generatedMapper.adapters
                .map(ColumnAdapterReference::getPropertyName)

            val propertyBuilder = PropertySpec.builder(
                propertyName,
                generatedMapper.className,
                KModifier.PRIVATE
            ).initializer(generatedMapper.className.asInitializer(parameters))
            classBuilder.addProperty(propertyBuilder.build())
        }

        generatedQueries.forEach { generatedQuery ->
            val propertyName = generatedQuery.className.asPropertyName()
            val parameters = ArrayList<String>()
            parameters.add(driverName)

            generatedQuery.adapters.forEach { adapter ->
                parameters.add(adapter.getPropertyName())
            }

            generatedQuery.mappers.forEach { mapper ->
                parameters.add(mapper.getPropertyName(options))
            }

            val propertyBuilder = PropertySpec.builder(
                propertyName,
                generatedQuery.className,
                KModifier.PRIVATE
            ).initializer(generatedQuery.className.asInitializer(parameters))

            classBuilder.addProperty(propertyBuilder.build())
        }

        databaseSpec.daoGetterSpecs.forEach { databaseDaoGetterSpec ->
            val queryClassName = databaseDaoGetterSpec.daoSpec.getQueryClassName(options)
            val daoClassName = databaseDaoGetterSpec.daoSpec.getDaoClassName(options)

            val parameters = listOf(queryClassName.asPropertyName())
            val propertyBuilder = PropertySpec.builder(
                databaseDaoGetterSpec.declaration.simpleName.asString(),
                daoClassName,
                KModifier.OVERRIDE
            ).initializer(daoClassName.asInitializer(parameters))

            classBuilder.addProperty(propertyBuilder.build())
        }

        classBuilder.addSchemeObject(
            databaseSpec,
            generatedTables
        )
/*

        databaseSpec.daoGetterSpecs.forEach { databaseDaoGetterSpec ->
            val generateResult = queriesGenerator.generate(databaseDaoGetterSpec.daoSpec)

            val parameters = ArrayList<String>()
            generateResult.adapters.forEach { adapter ->
                val adapterClassName = ColumnAdapter::class.asClassName()
                    .parameterizedBy(adapter.kotlinType, adapter.affinityType)

                val propertyName = adapter.getPropertyName()
                val adapterPropertySpec = PropertySpec.builder(
                    propertyName,
                    adapterClassName,
                    KModifier.PRIVATE
                ).initializer("null").build()

                classBuilder.addProperty(adapterPropertySpec)
                parameters.add(propertyName)
            }

            generateResult.mappers.forEach { mapper ->
                val mapperClassName = KabinEntityMapper::class.asClassName()
                    .parameterizedBy(mapper.entityType)

                val propertyName = mapper.getPropertyName()
                val adapterPropertySpec = PropertySpec.builder(
                    propertyName,
                    mapperClassName,
                    KModifier.PRIVATE
                ).initializer("null").build()

                classBuilder.addProperty(adapterPropertySpec)
                parameters.add(propertyName)
            }

            val queryClassName = databaseDaoGetterSpec.daoSpec.getQueryClassName(options)
            val queryPropertySpec = PropertySpec.builder(
                queryClassName.simpleName.toLowerCamelCase(),
                queryClassName,
                KModifier.PRIVATE
            ).initializer("${queryClassName.simpleName}($driverName, ${parameters.joinToString()})").build()

            classBuilder.addProperty(queryPropertySpec)
        }
*/

        codeGenerator.writeType(
            className,
            classBuilder.build()
        )
    }

    private fun TypeSpec.Builder.addSchemeObject(
        databaseSpec: DatabaseSpec,
        generatedTables: Set<TableGenerator.Result>
    ) {
        val classBuilder = TypeSpec.objectBuilder(SCHEME_NAME)

        val returnType = QueryResult.AsyncValue::class.asTypeName()
            .parameterizedBy(Unit::class.asTypeName())

        val superClassName = SqlSchema::class.asClassName()
            .parameterizedBy(returnType)

        classBuilder.addSuperinterface(superClassName)

        val versionPropertyBuilder = SqlSchema<*>::version.buildSpec()
            .addModifiers(KModifier.OVERRIDE)
            .initializer(databaseSpec.version.toString())

        classBuilder.addProperty(versionPropertyBuilder.build())

        val createFunction = SqlSchema<*>::create.buildSpec()
        val driverName = createFunction.parameters.first().name
        val createFunctionBuilder = createFunction
            .addModifiers(KModifier.OVERRIDE)
            .returns(returnType)

        val createFunctionCodeBuilder = CodeBlock.builder()
            .beginControlFlow("return %T", returnType)

        generatedTables.forEach { generatedTable ->
            createFunctionCodeBuilder.addStatement("%T.create($driverName)", generatedTable.className)
        }

        createFunctionCodeBuilder.endControlFlow()
        createFunctionBuilder.addCode(createFunctionCodeBuilder.build())

        classBuilder.addFunction(createFunctionBuilder.build())

        val migrateFunction = SqlSchema<*>::migrate.buildSpec()
        val migrateFunctionBuilder = migrateFunction
            .addModifiers(KModifier.OVERRIDE)
            .returns(returnType)

        val migrateFunctionCodeBuilder = CodeBlock.builder()
            .beginControlFlow("return %T", returnType)
            .addStatement("// TODO: Not yet implemented in Kabin")
            // TODO: Add migrations when support before close call
            .endControlFlow()

        migrateFunctionBuilder.addCode(migrateFunctionCodeBuilder.build())
        classBuilder.addFunction(migrateFunctionBuilder.build())

        addType(classBuilder.build())
    }
}

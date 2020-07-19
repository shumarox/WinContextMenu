package ice.util.win

import java.lang.reflect.{Field, Modifier}
import java.util

import com.sun.jna.{Pointer, Structure, TypeMapper}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object IStructure {
  private val modifiersField: Field = classOf[Field].getDeclaredField("modifiers")

  modifiersField.setAccessible(true)

  private def changeToPublic(field: Field): Unit = modifiersField.set(field, modifiersField.get(field).asInstanceOf[Int] | Modifier.PUBLIC)
}

abstract class IStructure(p: Pointer, alignType: Int, mapper: TypeMapper) extends Structure(p, alignType, mapper) {

  import IStructure._

  private var fieldList: util.List[Field] = _

  override protected def getFieldList: util.List[Field] = {

    if (fieldList == null) {
      val fields: mutable.ListBuffer[Field] = mutable.ListBuffer[Field]()

      var cls: Class[_] = getClass

      while (cls != classOf[IStructure]) {
        val methodNames = cls.getDeclaredMethods.map(_.getName)

        fields ++=
          cls.getDeclaredFields.filter { f =>
            !Modifier.isStatic(f.getModifiers)
          }.filter { f =>
            methodNames.contains(f.getName) && methodNames.contains(f.getName + "_$eq")
          }

        cls = cls.getSuperclass
      }

      fields.foreach(changeToPublic)

      fieldList = fields.asJava
    }

    fieldList
  }

  override protected final def getFieldOrder: util.List[String] = getFieldOrderList.asJava

  protected def getFieldOrderList: List[String]
}

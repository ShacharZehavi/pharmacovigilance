package com.zengurusoft.pharmacovigilance.tools

import org.apache.log4j.Logger
import java.io.File
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.StandardDecryptionMaterial
import org.apache.pdfbox.util.PDFTextStripper
import util.matching.Regex
import collection.mutable.ListBuffer
import jxl.Workbook
import jxl.write.{WritableSheet, Label, WritableWorkbook}
import java.util.{Locale, Date}

/**
 * ZenGuru Software
 * User: shachar
 * Date: 6/29/13
 * Time: 8:12 PM
*/
object PDFExtractor {
  lazy val logger = Logger.getLogger(this.getClass.getName)
  val ADVERSE_EVENT_PATTERN = new Regex("^\\s*\\[(\\d+)\\]")
  val ACTIVE_INGREDIENTS_PATTERN = new Regex("\\(.+?\\)")
  val ADVERSE_EVENTS_START_ANCHOR = "Adverse Events:"
  val ADVERSE_EVENTS_END_ANCHOR = "I. REACTION INFORMATION"
  val SUSPECTED_DRUGS_START_ANCHOR = "INITIAL FOLLOWUP"
  val SUSPECTED_DRUGS_END_ANCHOR = "Ferring Pharmaceuticals A/S"


  def main(args: Array[String]) {
    case class Config(inputDirectory:  String = "",
                      PDFPassword : String = "",
                      excelFile : String = ""
                      )
    val parser = new scopt.immutable.OptionParser[Config]("scopt", "2.x") { def options = Seq(
      opt("i", "inputDirectory", "Input directory") { (v: String, c: Config) => c.copy(inputDirectory = v) },
      opt("p", "PDFPassword", "PDF file password") { (v: String, c: Config) => c.copy(PDFPassword = v) },
      opt("o", "excelFile", "Excel output file") { (v: String, c: Config) => c.copy(excelFile = v) }
    ) }
    //
   parser.parse(args, Config()) map { config => {
     val format = new java.text.SimpleDateFormat("dd_MM_yyyy")
     val fileSuffix = format.format(new Date)
     val lastDotLocation = config.excelFile.lastIndexOf(".")
     val outputFile =
      if (lastDotLocation > 0)
        config.excelFile.substring(0, lastDotLocation-1) + "_" + fileSuffix  +
          config.excelFile.substring(lastDotLocation,config.excelFile.length)
     else
        config.excelFile
     val excel = createExcelDocument(outputFile)
     var currentLine = 1
     for(file <- (new File(config.inputDirectory)).listFiles if file.getName.toLowerCase endsWith ".pdf"){
       try {
          val eventId = file.getName.split("\\)")(0) + ")"
          logger.info(" ---------------------- Started Processing file: "+ eventId)
          val pages = convertPDFToText(file.getAbsolutePath, config.PDFPassword)
          val adverseEvents = processAdverseEvents(pages(0))
          val suspectedDrugs = processSuspectedDrugs(pages(0))
          currentLine = addEvents(excel.getSheet(0), currentLine, file.getName, adverseEvents, suspectedDrugs)
          currentLine+=1
          logger.info(" ---------------------- Finsiehd Processing file: "+ eventId)
       } catch {
         case exp: Exception => logger.error("Error processing file: " + file.getAbsolutePath, exp)
       }
     }
     excel.write()
     excel.close()
   }

   } getOrElse {
     logger.error("Bad arguments")
   }

  }

  def getEventId(fileName : String) : String = {
    fileName.substring(0, math.max(fileName.lastIndexOf('_'), fileName.lastIndexOf('-')))
  }


  def addEvents(sheet : WritableSheet, startLine : Int, eventId : String, adverseEvents : List[String], suspectedDrugs : List[(String, String)]) : Int = {
    //
    sheet.addCell(new Label(0, startLine, getEventId(eventId)))
    for( i <- 0 to suspectedDrugs.length -1) {
      sheet.addCell(new Label(1, startLine + i, suspectedDrugs(i)._1))
      sheet.addCell(new Label(3, startLine + i, suspectedDrugs(i)._2))
    }
    for( i <- 0 to adverseEvents.length -1)  sheet.addCell(new Label(2, startLine + i, adverseEvents(i)))
    logger.info("Hello")
    startLine + math.max(suspectedDrugs.length,  adverseEvents.length)
  }


  def createExcelDocument (excelFile : String) : WritableWorkbook = {
    val workBook = Workbook.createWorkbook(new File(excelFile))
    val sheet = workBook.createSheet("Alerts", 0)
    sheet.addCell(new Label(0, 0, "No."))
    sheet.addCell(new Label(1, 0, "Trade Name"))
    sheet.addCell(new Label(2, 0, "AE"))
    sheet.addCell(new Label(3, 0, "Active Ingredient"))
    sheet.addCell(new Label(4, 0, "Reported to MOH"))
    sheet.addCell(new Label(5, 0, "Reason"))
    sheet.addCell(new Label(6, 0, "Comments"))
    workBook
  }

  def convertPDFToText(fileName : String, password : String) : List[Array[String]] = {
    val pages = new ListBuffer[Array[String]]()
    val document = PDDocument.load(fileName)
    if(document.isEncrypted) document.openProtection(new StandardDecryptionMaterial(password))
    for (page <- 1 to document.getNumberOfPages) {
      val stripper = new PDFTextStripper()
      stripper.setStartPage(1)
      stripper.setEndPage(1)
      pages+=stripper.getText(document).split("\\n")
    }
    document.close()
    pages.toList
  }

  def processAdverseEvents(lines : Array[String]) : List[String] =  {
    var adverseEventsSection : Boolean = false
    val events = new ListBuffer[String]()
    lines.foreach(
      line =>{
        if (line.startsWith(ADVERSE_EVENTS_START_ANCHOR)) adverseEventsSection = true
        if (line.startsWith(ADVERSE_EVENTS_END_ANCHOR)) adverseEventsSection = false
        //
        if (adverseEventsSection && (ADVERSE_EVENT_PATTERN findFirstIn line).mkString(" ").length > 0) {
          val adverseEvent = line.split("\\,")(0)
          events+=adverseEvent
          logger.info("Adverse Event: " + adverseEvent)
        }
      })
    events.toList
  }

  /**
   *
   * @param lines
   * @return
   */
  def processSuspectedDrugs(lines : Array[String]) : List[(String, String)] =  {
    var suspectedDrugsSection : Boolean = false
    var lastItemNumber = 1
    val events = new ListBuffer[(String, String)]()
    lines.foreach(
      line =>{
        if (line.startsWith(SUSPECTED_DRUGS_START_ANCHOR)) suspectedDrugsSection = true
        if (line.startsWith(SUSPECTED_DRUGS_END_ANCHOR)) suspectedDrugsSection = false
        //
        val currentMatch = (ADVERSE_EVENT_PATTERN findAllIn line).mkString(" ").replaceAllLiterally("[","").replaceAllLiterally("]","")
        if (suspectedDrugsSection && currentMatch.length > 0 && lastItemNumber == currentMatch.toInt) {
          val suspectedDrug = line.split("\\,")(0)
          logger.info("Suspected drug: " + suspectedDrug)
          val activeIngredients = (ACTIVE_INGREDIENTS_PATTERN findFirstIn suspectedDrug).mkString(" ")
          events.+=((suspectedDrug.replaceAllLiterally(activeIngredients, ""), activeIngredients.substring(1, activeIngredients.length - 1)))
          lastItemNumber+=1
        }
      })
    events.toList
  }
}

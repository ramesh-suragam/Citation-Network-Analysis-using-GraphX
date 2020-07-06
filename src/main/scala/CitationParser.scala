import java.io.FileInputStream
import java.util.Properties

import net.liftweb.json.{DefaultFormats, _}
import org.apache.spark.graphx.{Edge, EdgeDirection, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.reflect.ClassTag

object CitationParser {

  def drawGraph[VD: ClassTag, ED: ClassTag](g: Graph[VD, ED]) = {

    val u = java.util.UUID.randomUUID
    val v = g.vertices.collect.map(_._1)
    println(
      """%html
<div id='a""" + u +
        """' style='width:960px; height:500px'></div>
<style>
.node circle { fill: gray; }
.node text { font: 10px sans-serif;
     text-anchor: middle;
     fill: white; }
line.link { stroke: gray;
    stroke-width: 1.5px; }
</style>
<script src="//d3js.org/d3.v3.min.js"></script>
<script>
.var width = 960, height = 500;
var svg = d3.select("#a""" + u +
        """").append("svg")
.attr("width", width).attr("height", height);
var nodes = [""" + v.map("{id:" + _ + "}").mkString(",") +
        """];
var links = [""" + g.edges.collect.map(
        e => "{source:nodes[" + v.indexWhere(_ == e.srcId) +
          "],target:nodes[" +
          v.indexWhere(_ == e.dstId) + "]}").mkString(",") +
        """];
var link = svg.selectAll(".link").data(links);
link.enter().insert("line", ".node").attr("class", "link");
var node = svg.selectAll(".node").data(nodes);
var nodeEnter = node.enter().append("g").attr("class", "node")
nodeEnter.append("circle").attr("r", 8);
nodeEnter.append("text").attr("dy", "0.35em")
 .text(function(d) { return d.id; });
d3.layout.force().linkDistance(50).charge(-200).chargeDistance(300)
.friction(0.95).linkStrength(0.5).size([width, height])
.on("tick", function() {
link.attr("x1", function(d) { return d.source.x; })
  .attr("y1", function(d) { return d.source.y; })
  .attr("x2", function(d) { return d.target.x; })
  .attr("y2", function(d) { return d.target.y; });
node.attr("transform", function(d) {
return "translate(" + d.x + "," + d.y + ")";
});
}).nodes(nodes).links(links).start();
</script>
 """)
  }

  def main(args: Array[String]): Unit = {

    //    Creating a spark configuration
    val conf = new SparkConf()
    conf.setMaster("local[*]")
      .setAppName("Citation")

    //    Creating a spark context driver and setting log level to error
    val sc = new SparkContext(conf)
    sc.setLogLevel("ERROR")

    //    Getting the properties for the environment
    val prop =
      try {
        val prop = new Properties()
        if (System.getenv("PATH").contains("Windows")) {
          prop.load(new FileInputStream("application-local.properties"))
        } else if (System.getenv("PATH").contains("ichec")) {
          prop.load(new FileInputStream("application-ichec.properties"))
        } else {
          println("Issue identifying the environment, PATH is:", System.getenv("PATH"))
        }
        (prop)
      } catch {
        case e: Exception =>
          e.printStackTrace()
          sys.exit(1)
      }

    //    Reading file to rdd
    println("Reading file to RDD...")
    val lines_orig = sc.textFile(prop.getProperty("file.path"))
    val lines = lines_orig.sample(false, prop.getProperty("sample.size").toDouble, 2)

    lines.take(8).foreach(println)
    println("RDD created!")

    println(s"Number of entries in linesRDD is ${lines.count()}") //1000000

    //    extracting the data using lift json parser
    println("Extracting the data using lift json parser...")
    val publicationRdd: RDD[Publication] = lines.map(x => {
      implicit val formats: DefaultFormats.type = DefaultFormats;
      parse(x).extract[Publication]
    }).cache()
    println("publicationRdd created!")

    //    val publicationGraph = getPublicationGraph(lines, publicationRdd)

    //    println(s"Total Number of publications: ${publicationGraph.numVertices}")
    //    println(s"Total Number of citations: ${publicationGraph.numEdges}")
    //    println("printing vertices")
    //    println(s"${publicationGraph.vertices.take(10).foreach(println)}")
    //    println("printing edges")
    //    println(s"${publicationGraph.edges.take(10).foreach(println)}")

    println("Finding the most influential citations")

    //    println("creating rank: Started");
    //    val ranks = graph.pageRank(0.1).vertices
    //    println("creating rank: Completed");
    //
    //    println("sorting and printing ranks");
    //      ranks
    //      .join(publicationVertices)
    //      .sortBy(_._2._1, ascending=false) // sort by the rank
    //      .take(10) // get the top 10
    //      .foreach(x => println(x._2._2))
    //    println("printing ranks: Completed");

    val journalGraph = getJournalGraph(lines, publicationRdd)
    //    println(s"Total number of journal vertices: ${journalGraph.numVertices}")
    //    println(s"Total number of journal edges: ${journalGraph.numEdges}")

    //    println("Please enter the journal name to search: ")
    //    val testJournal = scala.io.StdIn.readLine()
    //
    //    journalGraph.inDegrees.filter(_._1.equals(testJournal)).foreach(println)

    val pageRank = journalGraph.pageRank(0.0001).cache().vertices
    //    pageRank.join(journalGraph.vertices)
    //    println("sorting and printing ranks");
    //          pageRank
    //          .join(journalGraph.vertices)
    //          .sortBy(_._2._1, ascending=false) // sort by the rank
    //          .take(8) // get the top 10
    //          .foreach(x => println(x._2._2, x._2._1, x._1))
    //        println("printing ranks: Completed");

    //        drawGraph(journalGraph

    val inDegrees = journalGraph.inDegrees
    val outDegrees = journalGraph.outDegrees

    val journalGraphWithRank = pageRank.join(journalGraph.vertices)
    println("indegrees:")
    inDegrees.foreach(println)
    println("outdegrees:")
    outDegrees.foreach(println)

    val jourGraphWithDegrees = journalGraph.mapVertices {
      case (jid, jname) =>
        JournalWithDegrees(jid, jname, 0, 0, 0.0)
    }

    val degreeGraph = jourGraphWithDegrees.outerJoinVertices(jourGraphWithDegrees.inDegrees) {
      (jid, j, inDegOpt) => JournalWithDegrees(j.jid, j.journalName, inDegOpt.getOrElse(0), j.outDeg, j.pr)
    }.outerJoinVertices(jourGraphWithDegrees.outDegrees) {
      (jid, j, outDegOpt) => JournalWithDegrees(j.jid, j.journalName, j.inDeg, outDegOpt.getOrElse(0), j.pr)
    }.outerJoinVertices(jourGraphWithDegrees.pageRank(0.0001).vertices) {
      (jid, j, prOpt) => JournalWithDegrees(j.jid, j.journalName, j.inDeg, j.outDeg, prOpt.getOrElse(0))
    }

    degreeGraph.vertices.foreach(println)

    val result = degreeGraph.vertices.filter {
      case (jid, jv) => (jv.journalName.equals("J3"))
    }.first

    val result2 = degreeGraph.collectNeighbors(EdgeDirection.Out).lookup(result._1)
    println("after filter:")

    result2.map(x => x.foreach(y => println(y._2)))

    sc.stop()
    println("end")
  }

  def getJournalGraph(lines: RDD[String], publicationRdd: RDD[Publication]) = {

    //    create journal RDD vertices with publications
    val publicationGroups = publicationRdd.groupBy(publication => publication.journalName)
    //    val journalVertices: Unit = journalRdd.foreach(publicationGroup => Journal(publicationGroup._1,publicationGroup._2.toList))
    val journalRDD: RDD[Journal] = publicationGroups.map(x => Journal(x._1, x._2.toList)).distinct

    println("creating journal vertices...")
    val journalWithIndex = journalRDD.zipWithIndex()
    val journalVertices = journalWithIndex.map { case (k, v) => (v, k) }
    val journalVertices2 = journalVertices.map {
      case (k, v) => (k, v.journalName)
    }
    val journalVertices3 = journalVertices2.map { case (k, v) => (v, k) }

    val journalPublicDict = journalVertices.flatMap {
      case (jid, journal) =>
        journal.publications.map(p => (p.id, jid))
    }.collectAsMap()

    //    val journalVertices = journalRDD.map(journal => (hex2dec(journal.journalName).toLong, journal.publications)).distinct
    //    val journalVertices = journalRDD.map(journal => (withIndex.filter(x => x._1.equals(journal)))).distinct
    println("journal vertices created!")

    val nocitation = "nocitation"

    val testEdges = journalVertices.flatMap {
      case (jid, journal) =>
        journal.publications.flatMap(
          publication => publication.outCitations.map(
            outCitation => (jid,
              journalPublicDict.getOrElse(outCitation, -1.toLong)
              , 1)))
    }

    val testEdges2 = testEdges.filter(te => te._1 != te._2).filter(te => te._2 != -1)

    val zeroVal = 0
    val addToCounts = (acc: Int, ele: Int) => (acc + ele)
    val sumPartitionCounts = (acc1: Int, acc2: Int) => (acc1 + acc2)

    println("creating journal edges...")
    val journalEdges = testEdges2.map(te2 => ((te2._1, te2._2), te2._3)).aggregateByKey(0)(addToCounts, sumPartitionCounts).map(x => Edge(x._1._1, x._1._2, x._2))
    println("journal edges created!")

    Graph(journalVertices2, journalEdges, nocitation)
  }

  //  define the method to get publication graph
  def getPublicationGraph(lines: RDD[String], publicationRdd: RDD[Publication]): Graph[String, Int] = {

    //    println(s"Number of entries in publicationRdd is ${publicationRdd.count()}")
    //    printing the values of the publications
    //    publicationRdd.foreach(x => println(x.outCitations))

    //    create publication RDD vertices with ID and Name
    println("creating publication vertices...")
    val publicationVertices: RDD[(Long, String)] = publicationRdd.map(publication => (hex2dec(publication.id).toLong, publication.journalName)).distinct
    println("publication vertices created!")

    //    println(s"Number of entries in publicationVerticesRDD is ${publicationVertices.count()}") //1000000
    //    printing the values of the vertex
    //    publicationVertices.foreach(x => println(x._1))

    //     Defining a default vertex called nocitation
    val nocitation = "nocitation"

    ////      Map publication ID to the publication name to be printed
    //        val publicationVertexMap = publicationVertices.map(publication =>{
    //          case (publication._1, name) =>
    //            publication._1 -> name
    //        }).collect.toList.toMap

    //    Creating edges with outCitations and inCitations
    println("creating citations...")
    val citations = publicationRdd.map(publication => ((hex2dec(publication.id).toLong, publication.outCitations), 1)).distinct
    println("citations created!")

    //    println(s"Number of entries in citationsRDD is ${citations.count()}") //1000000
    //    printing citation values
    //    citations.foreach(x => println(x._1,x._2))
    //    println("creating citation edges...")
    //    creating citation edges with outCitations and inCitations
    //    val citationEdges= citations.map{
    //      case(id,outCitations) => for(outCitation <- outCitations){
    //        val longOutCit = hex2dec(outCitation).toLong
    ////        println(id,longOutCit)
    //        Edge(id,hex2dec(outCitation).toLong)
    //      }
    //    }

    println("creating citation edges with outCitations and inCitations")
    //    creating citation edges with outCitations and inCitations
    val citationEdges = citations.flatMap {
      case ((id, outCitations), num) =>
        outCitations.map(outCitation => Edge(id, hex2dec(outCitation).toLong, num))
    }
    println("citation edges created!")

    //    println(s"Number of entries in citationEdges is ${citationEdges.count()}")
    //    val citationEdges= citations.map{ case(id,outCitations) => outCitations.foreach(outCitation => Edge(id,hex2dec(outCitation).toLong))}}
    //    val citationEdges = citations.map {
    //    case (id, outCitations) =>Edge(org_id.toLong, dest_id.toLong, distance) }
    //    println(s"Number of entries in citationEdgesRDD is ${citationEdges.count()}")
    //    println(s"${citationEdges.take(10).foreach(println)}")
    //    citationEdges.foreach(println)

    println("creating publicationgraph...")
    //    creating publication graph
    Graph(publicationVertices, citationEdges, nocitation)
  }

  //  define the method to convert string to BigInt
  def hex2dec(hex: String): BigInt = {
    hex.toLowerCase().toList.map(
      "0123456789abcdef".indexOf(_)).map(
      BigInt(_)).reduceLeft(_ * 16 + _)
  }

  case class JournalWithDegrees(
                                 jid: VertexId,
                                 journalName: String,
                                 inDeg: Int,
                                 outDeg: Int,
                                 pr: Double
                               )

  //  define the journal schema
  case class Journal(
                      journalName: String,
                      publications: List[Publication]
                    )

  //  define the publication schema
  case class Publication(
                          entities: List[String],
                          journalVolume: Option[String],
                          journalPages: String,
                          pmid: String,
                          year: Option[Int],
                          outCitations: List[String],
                          s2Url: String,
                          s2PdfUrl: String,
                          id: String,
                          authors: List[Authors],
                          journalName: String,
                          paperAbstract: String,
                          inCitations: List[String],
                          pdfUrls: List[String],
                          title: String,
                          doi: String,
                          sources: List[String],
                          doiUrl: String,
                          venue: String)

  //  define the author schema
  case class Authors(
                      name: String,
                      ids: List[String]
                    )

}


object BISMOTestHelper{
    // random number generation functions
    val r = scala.util.Random

    def treatAs2sCompl(in: Int, nBits: Int): Int = {
        val negOffset = (1 << (nBits-1))
        return if(in >= negOffset) in-2*negOffset else in
    }

    def dotProduct(a: Seq[Int], b: Seq[Int]): Int = {
        return (a zip b).map{case (x,y) => x*y}.reduce(_+_)
    }

    def randomIntVector(len: Int, nBits: Int, allowNeg: Boolean): Seq[Int] = {
        val seq = for (i <- 1 to len) yield r.nextInt(1 << nBits)

        if(allowNeg) {
            return seq.map(i => treatAs2sCompl(i, nBits))
        } else {
            return seq
        }
    }
}
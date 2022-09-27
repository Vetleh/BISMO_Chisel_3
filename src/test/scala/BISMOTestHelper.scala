package bismo

object BISMOTestHelper {
  // random number generation functions
  val r = scala.util.Random

  def treatAs2sCompl(in: Int, nBits: Int): Int = {
    val negOffset = (1 << (nBits - 1))
    return if (in >= negOffset) in - 2 * negOffset else in
  }

  def dotProduct(a: Seq[Int], b: Seq[Int]): Int = {
    return (a zip b).map { case (x, y) => x * y }.reduce(_ + _)
  }

  def randomIntVector(len: Int, nBits: Int, allowNeg: Boolean): Seq[Int] = {
    val seq = for (i <- 1 to len) yield r.nextInt(1 << nBits)
    if (allowNeg) {
      return seq.map(i => treatAs2sCompl(i, nBits))
    } else {
      return seq
    }
  }

  // extract bit position at pos from number in
  def extractBitPos(in: Int, pos: Int, nBits: Int): Int = {
    val negOffset = (1 << (nBits - 1))
    if (in < 0) {
      return ((in + 2 * negOffset) & (1 << pos)) >> pos
    } else {
      return (in & (1 << pos)) >> pos
    }
  }

  // convert a sequence of integers into bit-serial form
  // e.g. [2, 0, 3] turns to [[0, 0, 1], [1, 0, 1]]
  def intVectorToBitSerial(in: Seq[Int], nBits: Int): Seq[Seq[Int]] = {
    for (i <- 0 to nBits - 1) yield in.map(x => extractBitPos(x, i, nBits))
  }

  // reshape a vector of integers into a matrix, treating the vector as a
  // row-major matrix
  def vectorToMatrix(in: Seq[Int], rows: Int, cols: Int): Seq[Seq[Int]] = {
    return for (i <- 0 to rows - 1) yield in.slice(i * cols, (i + 1) * cols)
  }

  // compute the product of two matrices a and b. assume a's first index returns
  // rows (row major), while b's first index returns columns (column major)
  // i.e. with sizes: a[m][k] x b[n][k] = c[m][n]
  def matrixProduct(a: Seq[Seq[Int]], b: Seq[Seq[Int]]): Seq[Seq[Int]] = {
    // ensure correct dimensions
    assert(a(0).size == b(0).size)
    // pairwise dot products
    val ret =
      for (i <- 0 to a.size - 1)
        yield for (j <- 0 to b.size - 1) yield dotProduct(a(i), b(j))
    return ret
  }

  // generate a matrix of random integers with desired size
  def randomIntMatrix(
      row: Int,
      col: Int,
      nBits: Int,
      allowNeg: Boolean
  ): Seq[Seq[Int]] = {
    return vectorToMatrix(randomIntVector(row * col, nBits, allowNeg), row, col)
  }
}

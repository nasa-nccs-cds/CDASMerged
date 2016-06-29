from nasa.cds2.client.CDAS_API import *

server = "localhost"
port = 9000


if __name__ == "__main__":

    exeReq = CDASExecuteRequest(server,port)
    v0 = Variable( uid="v0", uri="file:/Users/tpmaxwel/data/MERRA/MERRA300.prod.assim.inst3_3d_asm_Cp.xml", varname="t", domain_id="d0" )
    exeReq.addInputVariable( v0 )
    d0 = Domain(id="d0")
    lat = Axis( id="lat", start=45, end=45, system="values")
    lon = Axis( id="lon", start=30, end=30, system="values")
    lev = Axis( id="lev", start=3, end=3, system="indices")
    for axis in [lat,lon,lev]: d0.addAxis ( axis )
    exeReq.addDomain( d0 )
    op = Operation( package="CDS", kernel="anomaly", input_uids=[ "v0"], args={ "axes":"t" } )
    exeReq.addOperation( op )
    print exeReq.execute( async=False )
